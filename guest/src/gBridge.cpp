/*
 * armacraft bridge (guest side). See gBridge.h and armacraft PROTOCOL.md v1.
 *
 * Design notes
 * - One non-blocking TCP listen socket, polled from the server main loop. No threads:
 *   everything touches game state from the main thread only.
 * - Per-connection input/output buffers; newline-delimited JSON; partial reads/writes
 *   are buffered. A connection whose output backlog grows too large is dropped
 *   (state snapshots are skipped for it first), so a slow host never blocks the game.
 * - Events (spawn/death/turn) are derived by diffing cycle state every frame, which
 *   needs no hooks inside the simulation. Round start/end come from two hooks in gGame.
 * - Bridge players are plain server-owned ePlayerNetIDs (like AI players are, minus the
 *   AI), so real clients see ordinary players. Their cycles only move on bridge input.
 * - Armagetron 0.2.9 strings are ISO Latin-1 internally; we convert at the JSON boundary.
 */

#include "gBridge.h"

#include "gGame.h"
#include "gCycle.h"
#include "gCycleMovement.h"
#include "gWall.h"

#include "eGrid.h"
#include "eTeam.h"
#include "ePlayer.h"
#include "eTimer.h"
#include "eAdvWall.h"
#include "eRectangle.h"
#include "eNetGameObject.h"

#include "nNetwork.h"
#include "nNetObject.h"

#include "tConfiguration.h"
#include "tConsole.h"
#include "tString.h"
#include "tSysTime.h"

#include <string>
#include <vector>
#include <map>
#include <set>
#include <sstream>
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cmath>

#ifndef WIN32
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#define ARMACRAFT_BRIDGE_SOCKETS 1
#endif

// not declared in any header, but non-static in ePlayer.cpp
void se_BroadcastChat( ePlayerNetID* sender, const tString& say );
// gCycleMovement.cpp
extern REAL sg_suicideTimeout;

// ****************************************************************************
// settings
// ****************************************************************************

static bool sg_bridgeEnabled = false;
static tSettingItem< bool > sg_bridgeEnabledConf( "BRIDGE_ENABLED", sg_bridgeEnabled );

static int sg_bridgePort = 4535;
static tSettingItem< int > sg_bridgePortConf( "BRIDGE_PORT", sg_bridgePort );

static tString sg_bridgeBind( "127.0.0.1" );
static tSettingItem< tString > sg_bridgeBindConf( "BRIDGE_BIND", sg_bridgeBind );

static REAL sg_bridgeRate = 30;
static tSettingItem< REAL > sg_bridgeRateConf( "BRIDGE_RATE", sg_bridgeRate );

// extra settings (not in the task spec): gate the `cmd` message, cap hosts and players
static bool sg_bridgeAllowCmd = true;
static tSettingItem< bool > sg_bridgeAllowCmdConf( "BRIDGE_ALLOW_CMD", sg_bridgeAllowCmd );

static int sg_bridgeMaxHosts = 8;
static tSettingItem< int > sg_bridgeMaxHostsConf( "BRIDGE_MAX_HOSTS", sg_bridgeMaxHosts );

static int sg_bridgeMaxPlayersPerHost = 16;
static tSettingItem< int > sg_bridgeMaxPlayersPerHostConf( "BRIDGE_MAX_PLAYERS_PER_HOST", sg_bridgeMaxPlayersPerHost );

// limits
static const size_t sg_maxLine          = 64 * 1024;        // longest accepted input line
static const size_t sg_stateSkipBacklog = 1024 * 1024;      // skip state snapshots above this backlog
static const size_t sg_dropBacklog      = 16 * 1024 * 1024; // drop the host above this backlog

// ****************************************************************************
// string helpers (Latin-1 inside the game, UTF-8 on the wire)
// ****************************************************************************

static void sg_AppendUtf8( std::string & out, unsigned cp )
{
    if ( cp < 0x80 )
        out += char( cp );
    else if ( cp < 0x800 )
    {
        out += char( 0xC0 | ( cp >> 6 ) );
        out += char( 0x80 | ( cp & 0x3F ) );
    }
    else if ( cp < 0x10000 )
    {
        out += char( 0xE0 | ( cp >> 12 ) );
        out += char( 0x80 | ( ( cp >> 6 ) & 0x3F ) );
        out += char( 0x80 | ( cp & 0x3F ) );
    }
    else
    {
        out += char( 0xF0 | ( cp >> 18 ) );
        out += char( 0x80 | ( ( cp >> 12 ) & 0x3F ) );
        out += char( 0x80 | ( ( cp >> 6 ) & 0x3F ) );
        out += char( 0x80 | ( cp & 0x3F ) );
    }
}

// UTF-8 (from JSON) -> Latin-1 (for the game). Unmappable -> '?', control chars dropped.
static std::string sg_Utf8ToLatin1( std::string const & in, size_t maxLen )
{
    std::string out;
    size_t i = 0, n = in.size();
    while ( i < n && out.size() < maxLen )
    {
        unsigned char c = in[i];
        unsigned cp = 0;
        int extra = 0;
        if ( c < 0x80 ) { cp = c; extra = 0; }
        else if ( ( c & 0xE0 ) == 0xC0 ) { cp = c & 0x1F; extra = 1; }
        else if ( ( c & 0xF0 ) == 0xE0 ) { cp = c & 0x0F; extra = 2; }
        else if ( ( c & 0xF8 ) == 0xF0 ) { cp = c & 0x07; extra = 3; }
        else { ++i; out += '?'; continue; }
        bool ok = true;
        for ( int k = 1; k <= extra; ++k )
        {
            if ( i + k >= n || ( (unsigned char)in[i+k] & 0xC0 ) != 0x80 ) { ok = false; break; }
            cp = ( cp << 6 ) | ( (unsigned char)in[i+k] & 0x3F );
        }
        if ( !ok ) { ++i; out += '?'; continue; }
        i += 1 + extra;
        if ( cp < 0x20 || cp == 0x7F || ( cp >= 0x80 && cp < 0xA0 ) )
            continue;
        out += ( cp <= 0xFF ) ? char( cp ) : '?';
    }
    return out;
}

// JSON string literal from a Latin-1 game string
static void sg_JsonStr( std::string & out, char const * s )
{
    out += '"';
    for ( unsigned char const * p = (unsigned char const *)s; p && *p; ++p )
    {
        unsigned char ch = *p;
        switch ( ch )
        {
        case '"':  out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
            if ( ch < 0x20 || ch == 0x7F )
            {
                char buf[8];
                snprintf( buf, sizeof buf, "\\u%04x", ch );
                out += buf;
            }
            else if ( ch >= 0x80 )
                sg_AppendUtf8( out, ch );
            else
                out += char( ch );
        }
    }
    out += '"';
}

// JSON string literal from an already-UTF-8 string (keys we got from the host)
static void sg_JsonStrUtf8( std::string & out, std::string const & s )
{
    out += '"';
    for ( size_t i = 0; i < s.size(); ++i )
    {
        unsigned char ch = s[i];
        switch ( ch )
        {
        case '"':  out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        default:
            if ( ch < 0x20 || ch == 0x7F )
            {
                char buf[8];
                snprintf( buf, sizeof buf, "\\u%04x", ch );
                out += buf;
            }
            else
                out += char( ch );
        }
    }
    out += '"';
}

static void sg_JsonNum( std::string & out, double v )
{
    if ( !std::isfinite( v ) )
        v = 0;
    char buf[64];
    snprintf( buf, sizeof buf, "%.4f", v );
    // trim trailing zeros
    char * dot = strchr( buf, '.' );
    if ( dot )
    {
        char * end = buf + strlen( buf ) - 1;
        while ( end > dot && *end == '0' )
            *end-- = 0;
        if ( end == dot )
            *end = 0;
    }
    if ( 0 == strcmp( buf, "-0" ) )
        strcpy( buf, "0" );
    out += buf;
}

static void sg_JsonInt( std::string & out, long v )
{
    char buf[32];
    snprintf( buf, sizeof buf, "%ld", v );
    out += buf;
}

static std::string sg_PlainName( tString const & coloredOrPlain )
{
    return std::string( (char const *)tColoredString::RemoveColors( coloredOrPlain ) );
}

// ****************************************************************************
// minimal JSON parser
// ****************************************************************************

struct gJson
{
    enum Type { Null, False, True, Number, String, Array, Object };
    Type type;
    double num;
    std::string str;
    std::vector< gJson > arr;
    std::vector< std::pair< std::string, gJson > > obj;

    gJson(): type( Null ), num( 0 ) {}

    gJson const * Get( char const * key ) const
    {
        if ( type != Object )
            return 0;
        for ( size_t i = 0; i < obj.size(); ++i )
            if ( obj[i].first == key )
                return &obj[i].second;
        return 0;
    }

    bool IsString() const { return type == String; }
    bool IsNumber() const { return type == Number; }
    bool IsBool()   const { return type == True || type == False; }
};

class gJsonParser
{
public:
    explicit gJsonParser( std::string const & s ): p_( s.c_str() ), e_( s.c_str() + s.size() ) {}

    bool Parse( gJson & out )
    {
        if ( !Value( out, 0 ) )
            return false;
        Ws();
        return p_ == e_;
    }
private:
    char const * p_;
    char const * e_;

    void Ws()
    {
        while ( p_ < e_ && ( *p_ == ' ' || *p_ == '\t' || *p_ == '\n' || *p_ == '\r' ) )
            ++p_;
    }

    bool Lit( char const * s )
    {
        size_t n = strlen( s );
        if ( size_t( e_ - p_ ) >= n && 0 == strncmp( p_, s, n ) )
        {
            p_ += n;
            return true;
        }
        return false;
    }

    bool Value( gJson & v, int depth )
    {
        if ( depth > 32 )
            return false;
        Ws();
        if ( p_ >= e_ )
            return false;
        switch ( *p_ )
        {
        case '{': return Obj( v, depth );
        case '[': return Arr( v, depth );
        case '"': v.type = gJson::String; return Str( v.str );
        case 't': v.type = gJson::True;  return Lit( "true" );
        case 'f': v.type = gJson::False; return Lit( "false" );
        case 'n': v.type = gJson::Null;  return Lit( "null" );
        default:  return Num( v );
        }
    }

    bool Num( gJson & v )
    {
        char const * start = p_;
        if ( p_ < e_ && ( *p_ == '-' || *p_ == '+' ) ) ++p_;
        bool digits = false;
        while ( p_ < e_ && isdigit( (unsigned char)*p_ ) ) { ++p_; digits = true; }
        if ( p_ < e_ && *p_ == '.' )
        {
            ++p_;
            while ( p_ < e_ && isdigit( (unsigned char)*p_ ) ) { ++p_; digits = true; }
        }
        if ( !digits )
            return false;
        if ( p_ < e_ && ( *p_ == 'e' || *p_ == 'E' ) )
        {
            ++p_;
            if ( p_ < e_ && ( *p_ == '-' || *p_ == '+' ) ) ++p_;
            if ( p_ >= e_ || !isdigit( (unsigned char)*p_ ) )
                return false;
            while ( p_ < e_ && isdigit( (unsigned char)*p_ ) ) ++p_;
        }
        v.type = gJson::Number;
        v.num = strtod( std::string( start, p_ ).c_str(), 0 );
        return true;
    }

    bool Hex4( unsigned & cp )
    {
        if ( e_ - p_ < 4 )
            return false;
        cp = 0;
        for ( int i = 0; i < 4; ++i )
        {
            char c = *p_++;
            cp <<= 4;
            if ( c >= '0' && c <= '9' ) cp |= c - '0';
            else if ( c >= 'a' && c <= 'f' ) cp |= c - 'a' + 10;
            else if ( c >= 'A' && c <= 'F' ) cp |= c - 'A' + 10;
            else return false;
        }
        return true;
    }

    bool Str( std::string & out )
    {
        ++p_; // opening quote
        while ( p_ < e_ )
        {
            char c = *p_++;
            if ( c == '"' )
                return true;
            if ( c == '\\' )
            {
                if ( p_ >= e_ )
                    return false;
                char x = *p_++;
                switch ( x )
                {
                case '"':  out += '"'; break;
                case '\\': out += '\\'; break;
                case '/':  out += '/'; break;
                case 'b':  out += '\b'; break;
                case 'f':  out += '\f'; break;
                case 'n':  out += '\n'; break;
                case 'r':  out += '\r'; break;
                case 't':  out += '\t'; break;
                case 'u':
                {
                    unsigned cp;
                    if ( !Hex4( cp ) )
                        return false;
                    if ( cp >= 0xD800 && cp < 0xDC00 )
                    {
                        unsigned lo = 0;
                        if ( e_ - p_ >= 6 && p_[0] == '\\' && p_[1] == 'u' )
                        {
                            p_ += 2;
                            if ( !Hex4( lo ) )
                                return false;
                        }
                        if ( lo >= 0xDC00 && lo < 0xE000 )
                            cp = 0x10000 + ( ( cp - 0xD800 ) << 10 ) + ( lo - 0xDC00 );
                        else
                            cp = 0xFFFD;
                    }
                    else if ( cp >= 0xDC00 && cp < 0xE000 )
                        cp = 0xFFFD;
                    sg_AppendUtf8( out, cp );
                    break;
                }
                default:
                    return false;
                }
            }
            else if ( (unsigned char)c < 0x20 )
                return false;
            else
                out += c;
        }
        return false;
    }

    bool Arr( gJson & v, int depth )
    {
        v.type = gJson::Array;
        ++p_;
        Ws();
        if ( p_ < e_ && *p_ == ']' ) { ++p_; return true; }
        while ( true )
        {
            v.arr.push_back( gJson() );
            if ( !Value( v.arr.back(), depth + 1 ) )
                return false;
            Ws();
            if ( p_ >= e_ ) return false;
            if ( *p_ == ',' ) { ++p_; continue; }
            if ( *p_ == ']' ) { ++p_; return true; }
            return false;
        }
    }

    bool Obj( gJson & v, int depth )
    {
        v.type = gJson::Object;
        ++p_;
        Ws();
        if ( p_ < e_ && *p_ == '}' ) { ++p_; return true; }
        while ( true )
        {
            Ws();
            if ( p_ >= e_ || *p_ != '"' )
                return false;
            std::string key;
            if ( !Str( key ) )
                return false;
            Ws();
            if ( p_ >= e_ || *p_ != ':' )
                return false;
            ++p_;
            v.obj.push_back( std::make_pair( key, gJson() ) );
            if ( !Value( v.obj.back().second, depth + 1 ) )
                return false;
            Ws();
            if ( p_ >= e_ ) return false;
            if ( *p_ == ',' ) { ++p_; continue; }
            if ( *p_ == '}' ) { ++p_; return true; }
            return false;
        }
    }
};

// ****************************************************************************
// connections and bridge players
// ****************************************************************************

// what a delta host was last sent for one wall (exact values; only changed walls get formatted)
struct gWallSent
{
    int cycle;
    REAL x1, y1, x2, y2;
    std::vector< std::pair< REAL, REAL > > gaps;
    bool seen; // present in the frame being built

    gWallSent(): cycle( -1 ), x1( 0 ), y1( 0 ), x2( 0 ), y2( 0 ), seen( false ) {}
};

struct gBridgeConnection
{
    int id;
    int fd;
    std::string peer;
    std::string in;
    std::string out;
    bool dead;
    bool needRound;   // send `round` as soon as one is running
    int roundSeen;    // number of the last `round` this host received
    std::string why;  // reason for disconnecting
    bool delta;       // host opted in to `delta` messages (its hello listed features:["delta"])
    bool deltaKey;    // the next delta is a keyframe (opt-in, round start)
    unsigned long deltaSeq;
    std::map< int, gWallSent > sent; // delta wall id (net id, or -cycle for a head wall without one) -> last sent

    gBridgeConnection(): id( 0 ), fd( -1 ), dead( false ), needRound( true ), roundSeen( 0 ),
                         delta( false ), deltaKey( true ), deltaSeq( 0 ) {}
};

struct gBridgePlayer
{
    std::string key;     // UTF-8, as the host sent it
    int connection;      // gBridgeConnection::id of the owner
    tJUST_CONTROLLED_PTR< ePlayerNetID > player;
    bool brake;

    gBridgePlayer(): connection( 0 ), brake( false ) {}
};

// heap allocated and never freed: avoids static destruction order problems at exit
static std::vector< gBridgeConnection * > & sg_Connections()
{
    static std::vector< gBridgeConnection * > * list = new std::vector< gBridgeConnection * >();
    return *list;
}

static std::vector< gBridgePlayer * > & sg_Players()
{
    static std::vector< gBridgePlayer * > * list = new std::vector< gBridgePlayer * >();
    return *list;
}

static int sg_nextConnectionID = 1;

static gBridgePlayer * sg_FindPlayer( std::string const & key )
{
    std::vector< gBridgePlayer * > & players = sg_Players();
    for ( size_t i = 0; i < players.size(); ++i )
        if ( players[i]->key == key )
            return players[i];
    return 0;
}

static gBridgePlayer * sg_FindPlayer( ePlayerNetID const * p )
{
    if ( !p )
        return 0;
    std::vector< gBridgePlayer * > & players = sg_Players();
    for ( size_t i = 0; i < players.size(); ++i )
        if ( (ePlayerNetID const *)players[i]->player == p )
            return players[i];
    return 0;
}

static void sg_Send( gBridgeConnection & c, std::string const & line )
{
    if ( c.dead )
        return;
    c.out += line;
    c.out += '\n';
    if ( c.out.size() > sg_dropBacklog )
    {
        c.dead = true;
        c.why = "output backlog exceeded (slow host)";
    }
}

static void sg_SendAll( std::string const & line )
{
    std::vector< gBridgeConnection * > & conns = sg_Connections();
    for ( size_t i = 0; i < conns.size(); ++i )
        sg_Send( *conns[i], line );
}

static void sg_SendError( gBridgeConnection & c, std::string const & msg )
{
    std::string line = "{\"type\":\"error\",\"msg\":";
    sg_JsonStr( line, msg.c_str() );
    line += "}";
    sg_Send( c, line );
}

// removes the bridge player from the game and forgets it
static void sg_RemovePlayer( gBridgePlayer * bp, bool notify )
{
    std::vector< gBridgePlayer * > & players = sg_Players();
    players.erase( std::remove( players.begin(), players.end(), bp ), players.end() );

    if ( bp->player )
    {
        tJUST_CONTROLLED_PTR< ePlayerNetID > keep = bp->player;
        bp->player = NULL;
        con << "bridge: removing player " << keep->GetName() << " (" << bp->key.c_str() << ")\n";
        keep->RemoveFromGame();
    }

    if ( notify )
    {
        std::vector< gBridgeConnection * > & conns = sg_Connections();
        for ( size_t i = 0; i < conns.size(); ++i )
        {
            if ( conns[i]->id == bp->connection )
            {
                std::string line = "{\"type\":\"left\",\"key\":";
                sg_JsonStrUtf8( line, bp->key );
                line += "}";
                sg_Send( *conns[i], line );
            }
        }
    }

    delete bp;
}

static void sg_RemovePlayersOf( int connectionID )
{
    std::vector< gBridgePlayer * > copy = sg_Players();
    for ( size_t i = 0; i < copy.size(); ++i )
        if ( copy[i]->connection == connectionID )
            sg_RemovePlayer( copy[i], false );
}

// ****************************************************************************
// sockets
// ****************************************************************************

#ifdef ARMACRAFT_BRIDGE_SOCKETS
static int sg_listenFD = -1;
static int sg_listenPort = -1;
static std::string sg_listenBind;
static double sg_nextListenAttempt = 0;

static void sg_CloseConnection( gBridgeConnection * c )
{
    if ( c->fd >= 0 )
    {
        close( c->fd );
        c->fd = -1;
    }
}

static void sg_StopListening()
{
    if ( sg_listenFD >= 0 )
    {
        close( sg_listenFD );
        sg_listenFD = -1;
        con << "bridge: stopped listening\n";
    }
    sg_listenPort = -1;
    sg_listenBind = "";
}

static void sg_UpdateListener()
{
    std::string bind( (char const *)sg_bridgeBind );

    if ( !sg_bridgeEnabled )
    {
        if ( sg_listenFD >= 0 )
        {
            sg_StopListening();
            std::vector< gBridgeConnection * > & conns = sg_Connections();
            for ( size_t i = 0; i < conns.size(); ++i )
            {
                conns[i]->dead = true;
                conns[i]->why = "bridge disabled";
            }
        }
        return;
    }

    if ( sg_listenFD >= 0 && sg_listenPort == sg_bridgePort && sg_listenBind == bind )
        return;

    if ( sg_listenFD >= 0 )
        sg_StopListening();

    double now = tSysTimeFloat();
    if ( now < sg_nextListenAttempt )
        return;
    sg_nextListenAttempt = now + 5;

    struct sockaddr_in addr;
    memset( &addr, 0, sizeof addr );
    addr.sin_family = AF_INET;
    addr.sin_port = htons( (unsigned short)sg_bridgePort );
    if ( inet_pton( AF_INET, bind.c_str(), &addr.sin_addr ) != 1 )
    {
        con << "bridge: invalid BRIDGE_BIND address '" << bind.c_str() << "' (IPv4 dotted quad expected)\n";
        return;
    }

    int fd = socket( AF_INET, SOCK_STREAM, 0 );
    if ( fd < 0 )
    {
        con << "bridge: socket() failed: " << strerror( errno ) << "\n";
        return;
    }
    int one = 1;
    setsockopt( fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one );
    fcntl( fd, F_SETFL, fcntl( fd, F_GETFL ) | O_NONBLOCK );
    fcntl( fd, F_SETFD, FD_CLOEXEC );

    if ( ::bind( fd, (struct sockaddr *)&addr, sizeof addr ) < 0 || listen( fd, 8 ) < 0 )
    {
        con << "bridge: cannot listen on " << bind.c_str() << ":" << sg_bridgePort << ": " << strerror( errno ) << "\n";
        close( fd );
        return;
    }

    sg_listenFD = fd;
    sg_listenPort = sg_bridgePort;
    sg_listenBind = bind;
    con << "bridge: listening on " << bind.c_str() << ":" << sg_bridgePort << " (tcp)\n";
}

static void sg_SendHello( gBridgeConnection & c );

static void sg_Accept()
{
    if ( sg_listenFD < 0 )
        return;

    while ( true )
    {
        struct sockaddr_in addr;
        socklen_t len = sizeof addr;
        int fd = accept( sg_listenFD, (struct sockaddr *)&addr, &len );
        if ( fd < 0 )
            return; // EAGAIN or error; try again next frame

        char ip[64] = "?";
        inet_ntop( AF_INET, &addr.sin_addr, ip, sizeof ip );
        char peer[96];
        snprintf( peer, sizeof peer, "%s:%d", ip, ntohs( addr.sin_port ) );

        if ( int( sg_Connections().size() ) >= sg_bridgeMaxHosts )
        {
            static char const msg[] = "{\"type\":\"error\",\"msg\":\"too many bridge hosts\"}\n";
            ssize_t ignored = send( fd, msg, sizeof msg - 1, MSG_NOSIGNAL | MSG_DONTWAIT );
            (void)ignored;
            close( fd );
            con << "bridge: refused " << peer << " (BRIDGE_MAX_HOSTS reached)\n";
            continue;
        }

        fcntl( fd, F_SETFL, fcntl( fd, F_GETFL ) | O_NONBLOCK );
        fcntl( fd, F_SETFD, FD_CLOEXEC );
        int one = 1;
        setsockopt( fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one );

        gBridgeConnection * c = new gBridgeConnection();
        c->id = sg_nextConnectionID++;
        c->fd = fd;
        c->peer = peer;
        sg_Connections().push_back( c );
        con << "bridge: host #" << c->id << " connected from " << peer << "\n";

        sg_SendHello( *c );
    }
}

static void sg_Read( gBridgeConnection & c )
{
    if ( c.dead || c.fd < 0 )
        return;
    char buf[8192];
    while ( true )
    {
        ssize_t n = recv( c.fd, buf, sizeof buf, MSG_DONTWAIT );
        if ( n > 0 )
        {
            c.in.append( buf, n );
            if ( c.in.size() > sg_maxLine && c.in.find( '\n' ) == std::string::npos )
            {
                c.dead = true;
                c.why = "input line too long";
                return;
            }
            continue;
        }
        if ( n == 0 )
        {
            c.dead = true;
            c.why = "closed by host";
            return;
        }
        if ( errno == EINTR )
            continue;
        if ( errno != EAGAIN && errno != EWOULDBLOCK )
        {
            c.dead = true;
            c.why = strerror( errno );
        }
        return;
    }
}

static void sg_Flush( gBridgeConnection & c )
{
    if ( c.fd < 0 )
        return;
    size_t sent = 0;
    while ( sent < c.out.size() )
    {
        ssize_t n = send( c.fd, c.out.data() + sent, c.out.size() - sent, MSG_NOSIGNAL | MSG_DONTWAIT );
        if ( n > 0 )
        {
            sent += n;
            continue;
        }
        if ( n < 0 && errno == EINTR )
            continue;
        if ( n < 0 && errno != EAGAIN && errno != EWOULDBLOCK )
        {
            c.dead = true;
            c.why = strerror( errno );
        }
        break;
    }
    c.out.erase( 0, sent );
}
#else
// no socket support on this platform; the bridge stays inert
static void sg_UpdateListener() {}
static void sg_Accept() {}
static void sg_Read( gBridgeConnection & ) {}
static void sg_Flush( gBridgeConnection & ) {}
static void sg_CloseConnection( gBridgeConnection * ) {}
#endif

// >0 while host commands are being executed; a command could re-enter the main
// loop helpers (gGame::NetSyncIdle -> sg_BridgeIdle), so connections must not be freed then
static int sg_bridgeBusy = 0;

static void sg_ReapDead()
{
    if ( sg_bridgeBusy > 0 )
        return;
    std::vector< gBridgeConnection * > & conns = sg_Connections();
    for ( size_t i = 0; i < conns.size(); )
    {
        gBridgeConnection * c = conns[i];
        if ( c->dead )
        {
            // best effort: push out what is left (e.g. a final error)
            sg_Flush( *c );
            con << "bridge: host #" << c->id << " (" << c->peer.c_str() << ") disconnected: " << c->why.c_str() << "\n";
            sg_CloseConnection( c );
            conns.erase( conns.begin() + i );
            sg_RemovePlayersOf( c->id );
            delete c;
        }
        else
            ++i;
    }
}

// ****************************************************************************
// game state access (friend of gCycleMovement and gNetPlayerWall)
// ****************************************************************************

class gBridge
{
public:
    // id of the cycle that gets credit for killing c, or -1
    static int Killer( gCycle const * c )
    {
        gCycleMovement const & m = *c;
        ePlayerNetID const * enemy = m.enemyInfluence.GetEnemy();
        if ( !enemy || enemy == c->Player() || !enemy->Object() )
            return -1;
        if ( c->LastTime() - m.enemyInfluence.GetTime() > sg_suicideTimeout )
            return -1;
        return enemy->Object()->ID();
    }

    // The visible part of a player wall: endpoints a->b and holes as [from,to]
    // fractions along a->b. Returns false if nothing of the wall is visible.
    //
    // On a dedicated server the cycle's current wall is extrapolated *ahead* of the
    // cycle (CalculatePredictPosition with PREDICT_WALLS, ~0.15 s); that future part
    // is not dangerous yet and is clipped here so the wall ends at the cycle.
    static bool Visible( gNetPlayerWall const * w, REAL clipDist, eCoord & a, eCoord & b,
                         std::vector< std::pair< REAL, REAL > > & gaps )
    {
        gaps.clear();
        a = w->EndPoint( 0 );
        b = w->EndPoint( 1 );
        REAL beg = w->BegPos();
        REAL end = w->EndPos();
        if ( !( end - beg > 1E-4 ) )
            return false;

        REAL visEnd = end;
        gCycle const * c = w->Cycle();
        if ( c && clipDist < visEnd )
            visEnd = clipDist;
        if ( !( visEnd - beg > 1E-3 ) )
            return false; // entirely in the cycle's future
        if ( visEnd < end )
            b = a + ( b - a ) * ( ( visEnd - beg ) / ( end - beg ) );

        REAL span = visEnd - beg;
        tArray< gPlayerWallCoord > const & coords = w->coords_;
        for ( int i = 0; i + 1 < coords.Len(); ++i )
        {
            if ( !coords( i ).IsDangerous )
                gaps.push_back( std::make_pair( ( coords( i ).Pos - beg ) / span, ( coords( i + 1 ).Pos - beg ) / span ) );
        }

        // finite wall length: the tail has receded
        if ( c && gCycle::WallsLength() > 0 )
        {
            REAL cutoff = c->GetDistance() - c->ThisWallsLength();
            if ( cutoff >= visEnd )
                return false;
            if ( cutoff > beg )
                gaps.push_back( std::make_pair( REAL( 0 ), ( cutoff - beg ) / span ) );
        }

        // clamp, sort, merge
        for ( size_t i = 0; i < gaps.size(); ++i )
        {
            gaps[i].first  = std::max( REAL( 0 ), std::min( REAL( 1 ), gaps[i].first ) );
            gaps[i].second = std::max( REAL( 0 ), std::min( REAL( 1 ), gaps[i].second ) );
        }
        std::sort( gaps.begin(), gaps.end() );
        std::vector< std::pair< REAL, REAL > > merged;
        for ( size_t i = 0; i < gaps.size(); ++i )
        {
            if ( gaps[i].second - gaps[i].first < 1E-4 )
                continue;
            if ( !merged.empty() && gaps[i].first <= merged.back().second + 1E-4 )
                merged.back().second = std::max( merged.back().second, gaps[i].second );
            else
                merged.push_back( gaps[i] );
        }
        gaps.swap( merged );

        if ( gaps.size() == 1 && gaps[0].first <= 1E-4 && gaps[0].second >= 1 - 1E-4 )
            return false;
        return true;
    }
};

static void sg_CollectCycles( eGrid * grid, std::vector< gCycle * > & out )
{
    out.clear();
    if ( !grid )
        return;
    const tList< eGameObject > & active = grid->GameObjects();
    for ( int i = active.Len() - 1; i >= 0; --i )
    {
        gCycle * c = dynamic_cast< gCycle * >( active( i ) );
        if ( c )
            out.push_back( c );
    }
    const tList< eGameObject > & inactive = grid->GameObjectsInactive();
    for ( int i = inactive.Len() - 1; i >= 0; --i )
    {
        gCycle * c = dynamic_cast< gCycle * >( inactive( i ) );
        if ( c && std::find( out.begin(), out.end(), c ) == out.end() )
            out.push_back( c );
    }
    // cycles removed from the game (player left) whose walls are still standing:
    // keep them listed (dead) so every wall's `cycle` id resolves
    tList< gNetPlayerWall > * lists[2] = { &sg_netPlayerWalls, &sg_netPlayerWallsGridded };
    for ( int l = 0; l < 2; ++l )
    {
        for ( int i = lists[l]->Len() - 1; i >= 0; --i )
        {
            gNetPlayerWall * w = ( *lists[l] )( i );
            gCycle * c = w ? w->Cycle() : 0;
            if ( c && std::find( out.begin(), out.end(), c ) == out.end() )
                out.push_back( c );
        }
    }
    struct ByID { bool operator()( gCycle * a, gCycle * b ) const { return a->ID() < b->ID(); } };
    std::sort( out.begin(), out.end(), ByID() );
}

static std::string sg_CycleName( gCycle const * c )
{
    ePlayerNetID * p = c->Player();
    return p ? std::string( (char const *)p->GetName() ) : std::string();
}

static eCoord sg_UnitDir( gCycle const * c )
{
    eCoord d = c->Direction();
    REAL n = d.Norm();
    if ( n > 0 )
        d = d * ( 1 / n );
    return d;
}

// ****************************************************************************
// round / static geometry
// ****************************************************************************

// mirrors the GS_* defines private to gGame.cpp
static const int sg_GS_CREATE_OBJECTS = 20;

static bool sg_roundActive = false;
static int sg_roundNumber = 0;
static std::string sg_roundJson;     // cached `round` line for late joiners
static eGrid * sg_roundGrid = 0;

struct gSeg { REAL x1, y1, x2, y2; };

static bool sg_Near( REAL ax, REAL ay, REAL bx, REAL by )
{
    return fabs( ax - bx ) < 1E-3 && fabs( ay - by ) < 1E-3;
}

// merge collinear segments that continue each other (the grid splits rim walls)
static void sg_MergeSegments( std::vector< gSeg > & segs )
{
    bool changed = true;
    int guard = 0;
    while ( changed && guard++ < 1000 )
    {
        changed = false;
        for ( size_t i = 0; i < segs.size() && !changed; ++i )
        {
            for ( size_t j = i + 1; j < segs.size() && !changed; ++j )
            {
                gSeg a = segs[i], b = segs[j];
                // orient so that a ends where b starts, if they touch at all
                bool touch = false;
                for ( int flipA = 0; flipA < 2 && !touch; ++flipA )
                {
                    for ( int flipB = 0; flipB < 2 && !touch; ++flipB )
                    {
                        gSeg aa = a, bb = b;
                        if ( flipA ) { std::swap( aa.x1, aa.x2 ); std::swap( aa.y1, aa.y2 ); }
                        if ( flipB ) { std::swap( bb.x1, bb.x2 ); std::swap( bb.y1, bb.y2 ); }
                        if ( !sg_Near( aa.x2, aa.y2, bb.x1, bb.y1 ) )
                            continue;
                        REAL ux = aa.x2 - aa.x1, uy = aa.y2 - aa.y1;
                        REAL vx = bb.x2 - bb.x1, vy = bb.y2 - bb.y1;
                        REAL lu = sqrt( ux * ux + uy * uy ), lv = sqrt( vx * vx + vy * vy );
                        if ( lu < 1E-6 || lv < 1E-6 )
                            continue;
                        REAL cross = ( ux * vy - uy * vx ) / ( lu * lv );
                        REAL dot   = ( ux * vx + uy * vy ) / ( lu * lv );
                        if ( fabs( cross ) < 1E-4 && dot > 0 )
                        {
                            gSeg m = { aa.x1, aa.y1, bb.x2, bb.y2 };
                            segs[i] = m;
                            segs.erase( segs.begin() + j );
                            touch = changed = true;
                        }
                    }
                }
            }
        }
    }
}

static std::string sg_BuildRoundJson( REAL t )
{
    std::vector< gSeg > segs;
    for ( int i = se_rimWalls.Len() - 1; i >= 0; --i )
    {
        eWallRim * w = se_rimWalls( i );
        if ( !w )
            continue;
        eCoord a = w->EndPoint( 0 ), b = w->EndPoint( 1 );
        if ( ( b - a ).Norm() < 1E-6 )
            continue;
        gSeg s = { a.x, a.y, b.x, b.y };
        segs.push_back( s );
    }
    sg_MergeSegments( segs );

    std::string line = "{\"type\":\"round\",\"round\":";
    sg_JsonInt( line, sg_roundNumber );
    line += ",\"t\":";
    sg_JsonNum( line, t );
    line += ",\"static\":[";
    for ( size_t i = 0; i < segs.size(); ++i )
    {
        if ( i ) line += ',';
        line += '[';
        sg_JsonNum( line, segs[i].x1 ); line += ',';
        sg_JsonNum( line, segs[i].y1 ); line += ',';
        sg_JsonNum( line, segs[i].x2 ); line += ',';
        sg_JsonNum( line, segs[i].y2 );
        line += ']';
    }
    line += "],\"bounds\":[";
    eRectangle const & r = eWallRim::GetBounds();
    sg_JsonNum( line, r.GetLow().x );  line += ',';
    sg_JsonNum( line, r.GetLow().y );  line += ',';
    sg_JsonNum( line, r.GetHigh().x ); line += ',';
    sg_JsonNum( line, r.GetHigh().y );
    line += "]}";
    return line;
}

// ****************************************************************************
// events (diffing cycle state)
// ****************************************************************************

struct gTrack
{
    bool alive;
    unsigned short turns;
    eCoord pos;
    bool seen;
};

static std::map< int, gTrack > sg_tracks;
static std::string sg_candidateWinner;
static bool sg_hasCandidateWinner = false;
static int sg_maxAliveTeams = 0;

static void sg_EventHead( std::string & line, char const * ev, int id, eCoord const & pos )
{
    line = "{\"type\":\"event\",\"ev\":\"";
    line += ev;
    line += "\",\"id\":";
    sg_JsonInt( line, id );
    line += ",\"x\":";
    sg_JsonNum( line, pos.x );
    line += ",\"y\":";
    sg_JsonNum( line, pos.y );
}

static void sg_Track( eGrid * grid )
{
    REAL t = se_GameTime();
    std::vector< gCycle * > cycles;
    sg_CollectCycles( grid, cycles );

    for ( std::map< int, gTrack >::iterator it = sg_tracks.begin(); it != sg_tracks.end(); ++it )
        it->second.seen = false;

    std::set< eTeam * > aliveTeams;
    std::string line;

    for ( size_t i = 0; i < cycles.size(); ++i )
    {
        gCycle * c = cycles[i];
        int id = c->ID();
        bool alive = c->Alive();
        unsigned short turns = c->GetTurns();
        eCoord pos = c->Position();

        if ( alive && c->Team() )
            aliveTeams.insert( c->Team() );

        std::map< int, gTrack >::iterator it = sg_tracks.find( id );
        if ( it == sg_tracks.end() )
        {
            gTrack tr;
            tr.alive = alive;
            tr.turns = turns;
            tr.pos = pos;
            tr.seen = true;
            sg_tracks[id] = tr;
            if ( alive )
            {
                sg_EventHead( line, "spawn", id, pos );
                eCoord d = sg_UnitDir( c );
                line += ",\"dx\":"; sg_JsonNum( line, d.x );
                line += ",\"dy\":"; sg_JsonNum( line, d.y );
                line += ",\"name\":"; sg_JsonStr( line, sg_CycleName( c ).c_str() );
                line += ",\"t\":"; sg_JsonNum( line, t );
                line += "}";
                sg_SendAll( line );
            }
            continue;
        }

        gTrack & tr = it->second;
        tr.seen = true;

        if ( tr.alive && alive && turns != tr.turns )
        {
            eCoord tp = c->GetLastTurnPos();
            eCoord d = sg_UnitDir( c );
            sg_EventHead( line, "turn", id, tp );
            line += ",\"dx\":"; sg_JsonNum( line, d.x );
            line += ",\"dy\":"; sg_JsonNum( line, d.y );
            line += ",\"t\":"; sg_JsonNum( line, c->GetLastTurnTime() ); // simulation time of the turn
            line += "}";
            sg_SendAll( line );
        }

        if ( tr.alive && !alive )
        {
            sg_EventHead( line, "death", id, pos );
            int killer = gBridge::Killer( c );
            if ( killer >= 0 && killer != id )
            {
                line += ",\"killer\":";
                sg_JsonInt( line, killer );
            }
            line += ",\"t\":"; sg_JsonNum( line, c->DeathTime() );
            line += "}";
            sg_SendAll( line );
        }

        tr.alive = alive;
        tr.turns = turns;
        tr.pos = pos;
    }

    // cycles that vanished (player left mid round) count as dead
    for ( std::map< int, gTrack >::iterator it = sg_tracks.begin(); it != sg_tracks.end(); )
    {
        if ( !it->second.seen )
        {
            if ( it->second.alive )
            {
                sg_EventHead( line, "death", it->first, it->second.pos );
                line += ",\"removed\":true,\"t\":"; sg_JsonNum( line, t );
                line += "}";
                sg_SendAll( line );
            }
            sg_tracks.erase( it++ );
        }
        else
            ++it;
    }

    // last team standing (mirrors gGame::Analysis' notion of a round winner)
    int teams = int( aliveTeams.size() );
    if ( teams > sg_maxAliveTeams )
        sg_maxAliveTeams = teams;
    if ( teams == 1 && sg_maxAliveTeams >= 2 && !sg_hasCandidateWinner )
    {
        sg_hasCandidateWinner = true;
        sg_candidateWinner = sg_PlainName( ( *aliveTeams.begin() )->Name() );
    }
}

// ****************************************************************************
// state snapshots
// ****************************************************************************

static double sg_lastStateTime = -1E30;

// one visible player wall of the frame being emitted
struct gFrameWall
{
    int id, cycle;
    eCoord a, b;
    std::vector< std::pair< REAL, REAL > > gaps;
};

// everything a `state` or `delta` line needs, computed once per emission for all hosts
struct gFrame
{
    REAL t;
    std::string cycles; // body of the "cycles" array (always sent in full)
    std::vector< gFrameWall > walls;
};

static void sg_AppendWall( std::string & line, gFrameWall const & w, int id )
{
    line += "{\"id\":"; sg_JsonInt( line, id );
    line += ",\"cycle\":"; sg_JsonInt( line, w.cycle );
    line += ",\"x1\":"; sg_JsonNum( line, w.a.x );
    line += ",\"y1\":"; sg_JsonNum( line, w.a.y );
    line += ",\"x2\":"; sg_JsonNum( line, w.b.x );
    line += ",\"y2\":"; sg_JsonNum( line, w.b.y );
    if ( !w.gaps.empty() )
    {
        line += ",\"gaps\":[";
        for ( size_t g = 0; g < w.gaps.size(); ++g )
        {
            if ( g ) line += ',';
            line += '[';
            sg_JsonNum( line, w.gaps[g].first );
            line += ',';
            sg_JsonNum( line, w.gaps[g].second );
            line += ']';
        }
        line += ']';
    }
    line += '}';
}

static void sg_BuildFrame( eGrid * grid, gFrame & frame )
{
    REAL t = se_GameTime();
    frame.t = t;
    frame.walls.clear();
    std::vector< gCycle * > cycles;
    sg_CollectCycles( grid, cycles );

    std::string & line = frame.cycles;
    line.clear();
    line.reserve( 256 * ( cycles.size() + 1 ) );

    tList< gNetPlayerWall > * lists[2] = { &sg_netPlayerWalls, &sg_netPlayerWallsGridded };

    // The server simulates lazily: each cycle sits at its own LastTime() <= t (up to
    // ~1/DEDICATED_FPS behind). Extrapolate alive cycles to t, but never beyond the
    // server's own wall prediction (which stops at obstacles), so walls stay consistent.
    std::map< gCycle const *, REAL > predictedEnd; // furthest wall extent per cycle
    for ( int l = 0; l < 2; ++l )
        for ( int i = 0; i < lists[l]->Len(); ++i )
        {
            gNetPlayerWall * w = ( *lists[l] )( i );
            if ( w && w->Cycle() )
            {
                REAL e = w->EndPos();
                std::map< gCycle const *, REAL >::iterator it = predictedEnd.find( w->Cycle() );
                if ( it == predictedEnd.end() || it->second < e )
                    predictedEnd[ w->Cycle() ] = e;
            }
        }
    std::map< gCycle const *, REAL > advance;  // extrapolated distance per cycle
    for ( size_t i = 0; i < cycles.size(); ++i )
    {
        gCycle * c = cycles[i];
        REAL adv = 0;
        REAL lag = t - c->LastTime();
        if ( c->Alive() && lag > 0 && lag < .1 )
        {
            adv = c->Speed() * lag;
            std::map< gCycle const *, REAL >::iterator it = predictedEnd.find( c );
            REAL room = it != predictedEnd.end() ? it->second - c->GetDistance() : 0;
            adv = std::max( REAL( 0 ), std::min( adv, room ) );
        }
        advance[c] = adv;
    }

    for ( size_t i = 0; i < cycles.size(); ++i )
    {
        gCycle * c = cycles[i];
        if ( i ) line += ',';
        eCoord d = sg_UnitDir( c );
        eCoord pos = c->Position() + d * advance[c];
        line += "{\"id\":"; sg_JsonInt( line, c->ID() );
        line += ",\"name\":"; sg_JsonStr( line, sg_CycleName( c ).c_str() );
        line += ",\"color\":[";
        sg_JsonNum( line, std::max( REAL( 0 ), std::min( REAL( 1 ), c->color_.r ) ) ); line += ',';
        sg_JsonNum( line, std::max( REAL( 0 ), std::min( REAL( 1 ), c->color_.g ) ) ); line += ',';
        sg_JsonNum( line, std::max( REAL( 0 ), std::min( REAL( 1 ), c->color_.b ) ) );
        line += "],\"x\":"; sg_JsonNum( line, pos.x );
        line += ",\"y\":"; sg_JsonNum( line, pos.y );
        line += ",\"dx\":"; sg_JsonNum( line, d.x );
        line += ",\"dy\":"; sg_JsonNum( line, d.y );
        line += ",\"speed\":"; sg_JsonNum( line, c->Speed() );
        line += ",\"alive\":"; line += c->Alive() ? "true" : "false";
        line += ",\"brake\":"; line += c->GetBraking() ? "true" : "false";
        // rubber: fraction of the rubber budget used up (0 = fresh, 1 = depleted)
        REAL rubber = sg_rubberCycle > 0 ? c->GetRubber() / sg_rubberCycle : 0;
        line += ",\"rubber\":"; sg_JsonNum( line, std::max( REAL( 0 ), std::min( REAL( 1 ), rubber ) ) );
        gBridgePlayer * bp = sg_FindPlayer( c->Player() );
        if ( bp )
        {
            line += ",\"bridge_key\":";
            sg_JsonStrUtf8( line, bp->key );
        }
        line += "}";
    }

    std::vector< std::pair< REAL, REAL > > gaps;
    for ( int l = 0; l < 2; ++l )
    {
        tList< gNetPlayerWall > & list = *lists[l];
        for ( int i = 0; i < list.Len(); ++i )
        {
            gNetPlayerWall * w = list( i );
            if ( !w || !w->Cycle() )
                continue;
            if ( !w->IsDangerousAnywhere( t ) )
                continue; // gone (walls fall after death unless WALLS_STAY_UP_DELAY < 0)
            eCoord a, b;
            gCycle * wc = w->Cycle();
            REAL clip = wc->GetDistance() + ( advance.count( wc ) ? advance[wc] : REAL( 0 ) );
            if ( !gBridge::Visible( w, clip, a, b, gaps ) )
                continue;
            if ( ( b - a ).Norm() < 1E-3 )
                continue;

            frame.walls.push_back( gFrameWall() );
            gFrameWall & fw = frame.walls.back();
            fw.id = w->ID();
            fw.cycle = wc->ID();
            fw.a = a;
            fw.b = b;
            fw.gaps = gaps;
        }
    }
}

// v1 `state`: every wall, byte-identical to the pre-delta output
static std::string sg_FullStateJson( gFrame const & f )
{
    std::string line;
    line.reserve( 64 + f.cycles.size() + 96 * f.walls.size() );
    line = "{\"type\":\"state\",\"t\":";
    sg_JsonNum( line, f.t );
    line += ",\"cycles\":[";
    line += f.cycles;
    line += "],\"walls\":[";
    for ( size_t i = 0; i < f.walls.size(); ++i )
    {
        if ( i ) line += ',';
        sg_AppendWall( line, f.walls[i], f.walls[i].id );
    }
    line += "]}";
    return line;
}

// `delta` (opt-in): all cycles, only walls that are new or changed since what *this* host was last
// sent, and the ids that disappeared. Diffing against the per-host "last sent" map (not the previous
// frame) keeps skipped frames (slow host backlog) lossless.
static std::string sg_DeltaJson( gBridgeConnection & c, gFrame const & f )
{
    bool key = c.deltaKey;
    c.deltaKey = false;
    if ( key )
        c.sent.clear();

    std::string line;
    line.reserve( 128 + f.cycles.size() + 512 );
    line = "{\"type\":\"delta\",\"seq\":";
    sg_JsonInt( line, long( ++c.deltaSeq ) );
    line += ",\"key\":";
    line += key ? "true" : "false";
    line += ",\"t\":";
    sg_JsonNum( line, f.t );
    line += ",\"cycles\":[";
    line += f.cycles;
    line += "],\"walls\":[";

    for ( std::map< int, gWallSent >::iterator it = c.sent.begin(); it != c.sent.end(); ++it )
        it->second.seen = false;

    bool first = true;
    for ( size_t i = 0; i < f.walls.size(); ++i )
    {
        gFrameWall const & w = f.walls[i];
        // a cycle's current head wall has no net id yet (id 0 in `state`, one per alive cycle);
        // deltas need a stable key, so it travels as -cycle until it gets its real id
        int id = w.id > 0 ? w.id : -w.cycle;
        std::map< int, gWallSent >::iterator it = c.sent.find( id );
        bool changed = it == c.sent.end();
        if ( changed )
            it = c.sent.insert( std::make_pair( id, gWallSent() ) ).first;
        gWallSent & s = it->second;
        s.seen = true;
        if ( !changed && s.cycle == w.cycle && s.x1 == w.a.x && s.y1 == w.a.y && s.x2 == w.b.x && s.y2 == w.b.y && s.gaps == w.gaps )
            continue;
        s.cycle = w.cycle;
        s.x1 = w.a.x; s.y1 = w.a.y; s.x2 = w.b.x; s.y2 = w.b.y;
        s.gaps = w.gaps;
        if ( !first ) line += ',';
        first = false;
        sg_AppendWall( line, w, id );
    }

    line += "],\"removed\":[";
    first = true;
    for ( std::map< int, gWallSent >::iterator it = c.sent.begin(); it != c.sent.end(); )
    {
        if ( it->second.seen )
        {
            ++it;
            continue;
        }
        if ( !first ) line += ',';
        first = false;
        sg_JsonInt( line, it->first );
        c.sent.erase( it++ );
    }
    line += "]}";
    return line;
}

static void sg_EmitState( eGrid * grid )
{
    std::vector< gBridgeConnection * > & conns = sg_Connections();
    if ( conns.empty() || !grid )
        return;
    gFrame frame;
    bool built = false;
    std::string full;
    for ( size_t i = 0; i < conns.size(); ++i )
    {
        gBridgeConnection & c = *conns[i];
        // full snapshots: skipping one for a host that lags behind loses nothing; deltas diff against
        // what the host was last sent, so a skipped delta is folded into the next one
        if ( c.out.size() > sg_stateSkipBacklog || c.needRound )
            continue;
        if ( !built )
        {
            sg_BuildFrame( grid, frame );
            built = true;
        }
        if ( c.delta )
            sg_Send( c, sg_DeltaJson( c, frame ) );
        else
        {
            if ( full.empty() )
                full = sg_FullStateJson( frame );
            sg_Send( c, full );
        }
    }
}

// ****************************************************************************
// host -> guest messages
// ****************************************************************************

static void sg_SendHello( gBridgeConnection & c )
{
    std::string line = "{\"type\":\"hello\",\"proto\":1,\"game\":\"armagetronad\",\"version\":";
    sg_JsonStr( line, (char const *)sn_programVersion );
    line += ",\"rate\":";
    sg_JsonNum( line, sg_bridgeRate );
    line += ",\"features\":[\"delta\"]}";
    sg_Send( c, line );
}

// host -> guest `hello` (optional): {"type":"hello","proto":1,"features":["delta"]}
// `features` is the full set the host wants; a later hello replaces it ([] = back to plain v1).
static void sg_HandleHello( gBridgeConnection & c, gJson const & msg )
{
    gJson const * f = msg.Get( "features" );
    if ( !f || f->type != gJson::Array )
        return;
    bool delta = false;
    for ( size_t i = 0; i < f->arr.size(); ++i )
        if ( f->arr[i].IsString() && f->arr[i].str == "delta" )
            delta = true;
    if ( delta != c.delta )
    {
        c.delta = delta;
        c.deltaKey = true;
        c.sent.clear();
        con << "bridge: host #" << c.id << ( delta ? " uses delta snapshots\n" : " uses full snapshots\n" );
    }
}

static bool sg_GetString( gJson const & msg, char const * key, std::string & out )
{
    gJson const * v = msg.Get( key );
    if ( !v || !v->IsString() )
        return false;
    out = v->str;
    return true;
}

static void sg_HandleJoin( gBridgeConnection & c, gJson const & msg )
{
    std::string key, name;
    if ( !sg_GetString( msg, "key", key ) || key.empty() || key.size() > 128 )
    {
        sg_SendError( c, "join: missing or invalid key" );
        return;
    }
    if ( !sg_GetString( msg, "name", name ) )
        name = "mc";

    gBridgePlayer * existing = sg_FindPlayer( key );
    if ( existing )
    {
        if ( existing->connection != c.id )
        {
            sg_SendError( c, "join: key is in use by another host" );
            return;
        }
        // idempotent re-join
        std::string line = "{\"type\":\"joined\",\"key\":";
        sg_JsonStrUtf8( line, key );
        line += ",\"name\":";
        sg_JsonStr( line, existing->player ? (char const *)existing->player->GetName() : "" );
        line += "}";
        sg_Send( c, line );
        return;
    }

    int mine = 0;
    std::vector< gBridgePlayer * > & players = sg_Players();
    for ( size_t i = 0; i < players.size(); ++i )
        if ( players[i]->connection == c.id )
            ++mine;
    if ( mine >= sg_bridgeMaxPlayersPerHost )
    {
        sg_SendError( c, "join: BRIDGE_MAX_PLAYERS_PER_HOST reached" );
        return;
    }

    std::string latinName = sg_Utf8ToLatin1( name, 31 );
    if ( latinName.empty() )
        latinName = "mc";

    // a plain server-owned player, created like gAIPlayer but without any AI
    ePlayerNetID * p = tNEW( ePlayerNetID )( -1 );

    gBridgePlayer * bp = new gBridgePlayer();
    bp->key = key;
    bp->connection = c.id;
    bp->player = p;
    players.push_back( bp );

    gJson const * color = msg.Get( "color" );
    if ( color && color->type == gJson::Array && color->arr.size() >= 3 )
    {
        unsigned short * channels[3] = { &p->r, &p->g, &p->b };
        for ( int k = 0; k < 3; ++k )
        {
            double v = color->arr[k].IsNumber() ? color->arr[k].num : 1;
            if ( !std::isfinite( v ) ) v = 1;
            v = std::max( 0.0, std::min( 1.0, v ) );
            *channels[k] = (unsigned short)( floor( v * 15 + .5 ) );
        }
    }

    p->SetName( tString( latinName.c_str() ) );
    p->Activity();
    p->FindDefaultTeam();
    if ( !p->NextTeam() && !p->CurrentTeam() )
        p->CreateNewTeamWish();
    p->RequestSync();

    con << "bridge: host #" << c.id << " joined player " << p->GetName() << " (" << key.c_str() << ")\n";

    std::string line = "{\"type\":\"joined\",\"key\":";
    sg_JsonStrUtf8( line, key );
    line += ",\"name\":";
    sg_JsonStr( line, (char const *)p->GetName() );
    line += "}";
    sg_Send( c, line );
}

static gBridgePlayer * sg_OwnPlayer( gBridgeConnection & c, gJson const & msg, char const * what )
{
    std::string key;
    if ( !sg_GetString( msg, "key", key ) )
    {
        sg_SendError( c, std::string( what ) + ": missing key" );
        return 0;
    }
    gBridgePlayer * bp = sg_FindPlayer( key );
    if ( !bp || bp->connection != c.id )
    {
        sg_SendError( c, std::string( what ) + ": unknown key" );
        return 0;
    }
    return bp;
}

static void sg_HandleLeave( gBridgeConnection & c, gJson const & msg )
{
    gBridgePlayer * bp = sg_OwnPlayer( c, msg, "leave" );
    if ( bp )
        sg_RemovePlayer( bp, true );
}

static void sg_HandleInput( gBridgeConnection & c, gJson const & msg )
{
    gBridgePlayer * bp = sg_OwnPlayer( c, msg, "input" );
    if ( !bp || !bp->player )
        return;

    bp->player->Activity();
    gCycle * cycle = dynamic_cast< gCycle * >( bp->player->Object() );

    gJson const * brake = msg.Get( "brake" );
    if ( brake && brake->IsBool() )
    {
        bp->brake = brake->type == gJson::True;
        if ( cycle && cycle->Alive() )
            cycle->Act( &gCycle::s_brake, bp->brake ? 1 : -1 );
    }

    std::string turn;
    if ( sg_GetString( msg, "turn", turn ) )
    {
        if ( turn != "left" && turn != "right" )
        {
            sg_SendError( c, "input: turn must be \"left\" or \"right\"" );
            return;
        }
        // same path the AI uses: gCycle::Act -> Turn -> DoTurn (respects CYCLE_DELAY,
        // queues turns that come too early)
        if ( cycle && cycle->Alive() )
            cycle->Act( turn == "left" ? &gCycle::se_turnLeft : &gCycle::se_turnRight, 1 );
    }
}

static void sg_HandleExplode( gBridgeConnection & c, gJson const & msg, gGame * game )
{
    gJson const * jx = msg.Get( "x" );
    gJson const * jy = msg.Get( "y" );
    gJson const * jr = msg.Get( "r" );
    if ( !jx || !jy || !jr || !jx->IsNumber() || !jy->IsNumber() || !jr->IsNumber() ||
         !std::isfinite( jx->num ) || !std::isfinite( jy->num ) || !std::isfinite( jr->num ) || jr->num <= 0 )
    {
        sg_SendError( c, "explode: needs numeric x, y and r > 0" );
        return;
    }
    if ( !game || !sg_roundActive || se_GameTime() < 0 )
    {
        sg_SendError( c, "explode: no round in progress" );
        return;
    }

    eCoord center( jx->num, jy->num );
    REAL r = std::min( 1000.0, jr->num );
    REAL r2 = r * r;

    // 1. blow holes into every player wall that intersects the circle
    int holes = 0;
    tList< gNetPlayerWall > * lists[2] = { &sg_netPlayerWalls, &sg_netPlayerWallsGridded };
    std::vector< gNetPlayerWall * > walls;
    for ( int l = 0; l < 2; ++l )
        for ( int i = 0; i < lists[l]->Len(); ++i )
            walls.push_back( ( *lists[l] )( i ) );
    for ( size_t i = 0; i < walls.size(); ++i )
    {
        gNetPlayerWall * w = walls[i];
        if ( !w || !w->Cycle() )
            continue;
        eCoord a = w->EndPoint( 0 ), b = w->EndPoint( 1 );
        eCoord ab = b - a;
        REAL len2 = ab.NormSquared();
        if ( len2 < 1E-8 )
            continue;
        eCoord ac = center - a;
        REAL t0 = eCoord::F( ac, ab ) / len2;
        eCoord closest = a + ab * t0;
        REAL d2 = ( closest - center ).NormSquared();
        if ( d2 > r2 )
            continue;
        REAL half = sqrt( ( r2 - d2 ) / len2 );
        REAL ta = std::max( REAL( 0 ), t0 - half );
        REAL tb = std::min( REAL( 1 ), t0 + half );
        if ( tb <= ta )
            continue;
        REAL beg = w->BegPos(), end = w->EndPos();
        w->BlowHole( beg + ta * ( end - beg ), beg + tb * ( end - beg ), 0 );
        ++holes;
    }

    // 2. kill cycles inside the radius (normal death: explosion, scoring as suicide)
    int kills = 0;
    std::vector< gCycle * > cycles;
    sg_CollectCycles( game->Grid(), cycles );
    for ( size_t i = 0; i < cycles.size(); ++i )
    {
        gCycle * cy = cycles[i];
        if ( cy->Alive() && ( cy->Position() - center ).NormSquared() <= r2 )
        {
            cy->Kill();
            ++kills;
        }
    }

    con << "bridge: explode at (" << center.x << "," << center.y << ") r=" << r << ": " << holes << " walls holed, " << kills << " cycles killed\n";
}

static void sg_HandleCmd( gBridgeConnection & c, gJson const & msg )
{
    std::string line;
    if ( !sg_GetString( msg, "line", line ) )
    {
        sg_SendError( c, "cmd: missing line" );
        return;
    }
    if ( !sg_bridgeAllowCmd )
    {
        sg_SendError( c, "cmd: disabled (BRIDGE_ALLOW_CMD 0)" );
        return;
    }

    // keep newlines (several commands per message), drop other control characters
    std::string latin;
    {
        std::string cur;
        std::istringstream lines( line );
        while ( std::getline( lines, cur ) )
        {
            latin += sg_Utf8ToLatin1( cur, 4096 );
            latin += '\n';
        }
    }

    con << "bridge: host #" << c.id << " cmd: " << latin.c_str();

    // same as the stdin console path (rInputStream::HandleInput): owner level
    tCurrentAccessLevel level( tAccessLevel_Owner, true );
    std::istringstream s( latin );
    tConfItemBase::LoadAll( s, true );
}

static void sg_HandleChat( gBridgeConnection & c, gJson const & msg )
{
    std::string name, text;
    sg_GetString( msg, "name", name );
    if ( !sg_GetString( msg, "text", text ) )
    {
        sg_SendError( c, "chat: missing text" );
        return;
    }
    std::string latinText = sg_Utf8ToLatin1( text, 200 );
    std::string latinName = sg_Utf8ToLatin1( name, 31 );
    if ( latinText.empty() )
        return;

    // a bridge player of this host with that name speaks as itself
    std::vector< gBridgePlayer * > & players = sg_Players();
    for ( size_t i = 0; i < players.size(); ++i )
    {
        gBridgePlayer * bp = players[i];
        if ( bp->connection == c.id && bp->player &&
             ( latinName == (char const *)bp->player->GetName() || latinName == (char const *)bp->player->GetUserName() ) )
        {
            tString say( latinText.c_str() );
            se_BroadcastChat( bp->player, say );
            con << bp->player->GetName() << ": " << say << "\n";
            return;
        }
    }

    // otherwise: a server message
    tColoredString out;
    if ( !latinName.empty() )
    {
        out << tColoredString::ColorString( .5, 1, .5 ) << latinName.c_str()
            << tColoredString::ColorString( 1, 1, .5 ) << ": ";
    }
    out << tColoredString::ColorString( 1, 1, 1 ) << latinText.c_str() << "\n";
    sn_ConsoleOut( out );
}

static void sg_HandleLine( gBridgeConnection & c, std::string const & raw, gGame * game )
{
    // tolerate CRLF and blank lines
    std::string line = raw;
    while ( !line.empty() && ( line[line.size() - 1] == '\r' || line[line.size() - 1] == ' ' ) )
        line.erase( line.size() - 1 );
    if ( line.empty() )
        return;

    gJson msg;
    gJsonParser parser( line );
    if ( !parser.Parse( msg ) || msg.type != gJson::Object )
    {
        sg_SendError( c, "malformed JSON line" );
        return;
    }
    std::string type;
    if ( !sg_GetString( msg, "type", type ) )
    {
        sg_SendError( c, "missing type" );
        return;
    }

    if ( type == "hello" )
        sg_HandleHello( c, msg );
    else if ( type == "join" )
        sg_HandleJoin( c, msg );
    else if ( type == "leave" )
        sg_HandleLeave( c, msg );
    else if ( type == "input" )
        sg_HandleInput( c, msg );
    else if ( type == "explode" )
        sg_HandleExplode( c, msg, game );
    else if ( type == "cmd" )
        sg_HandleCmd( c, msg );
    else if ( type == "chat" )
        sg_HandleChat( c, msg );
    // unknown types are ignored (forward compatibility)
}

static void sg_ProcessInput( gGame * game )
{
    // connections may be appended/flagged while we handle lines; index loop is safe,
    // and none are freed until sg_bridgeBusy drops back to 0
    struct Busy { Busy() { ++sg_bridgeBusy; } ~Busy() { --sg_bridgeBusy; } } busy;
    std::vector< gBridgeConnection * > & conns = sg_Connections();
    for ( size_t i = 0; i < conns.size(); ++i )
    {
        gBridgeConnection * c = conns[i];
        size_t start = 0;
        while ( !c->dead )
        {
            size_t nl = c->in.find( '\n', start );
            if ( nl == std::string::npos )
                break;
            std::string line = c->in.substr( start, nl - start );
            start = nl + 1;
            sg_HandleLine( *c, line, game );
        }
        c->in.erase( 0, start );
    }
}

// ****************************************************************************
// chat observer (set into ePlayer.cpp's se_ChatShout via se_chatObserver)
// ****************************************************************************

static void sg_OnChat( ePlayerNetID * sender, tString const & say )
{
    if ( sg_Connections().empty() || !sender )
        return;
    std::string line = "{\"type\":\"event\",\"ev\":\"chat\",\"name\":";
    sg_JsonStr( line, (char const *)sender->GetName() );
    line += ",\"text\":";
    sg_JsonStr( line, sg_PlainName( say ).c_str() );
    if ( sg_roundActive )
    {
        line += ",\"t\":";
        sg_JsonNum( line, se_GameTime() );
    }
    line += "}";
    sg_SendAll( line );
}

namespace
{
struct gBridgeInit
{
    gBridgeInit()
    {
        se_chatObserver = &sg_OnChat;
    }
};
gBridgeInit sg_bridgeInit;
}

// ****************************************************************************
// hooks
// ****************************************************************************

static void sg_FlushAll()
{
    std::vector< gBridgeConnection * > & conns = sg_Connections();
    for ( size_t i = 0; i < conns.size(); ++i )
        sg_Flush( *conns[i] );
}

void sg_BridgeIdle()
{
    sg_UpdateListener();
    sg_Accept();
    std::vector< gBridgeConnection * > & conns = sg_Connections();
    for ( size_t i = 0; i < conns.size(); ++i )
        sg_Read( *conns[i] );
    sg_FlushAll();
    sg_ReapDead();
}

int sg_BridgeNumUsers()
{
    if ( !sg_bridgeEnabled )
        return 0;
    int n = 0;
    std::vector< gBridgeConnection * > & conns = sg_Connections();
    for ( size_t i = 0; i < conns.size(); ++i )
        if ( !conns[i]->dead )
            ++n;
    return n;
}

void sg_BridgeRoundStart( gGame * game )
{
    if ( !game )
        return;

    if ( sg_roundActive )
    {
        // the previous round never reached GS_DELETE_OBJECTS (game restarted). Close it
        // for the hosts that saw it; don't diff old cycles against the new grid.
        std::string line = "{\"type\":\"event\",\"ev\":\"round_end\",\"round\":";
        sg_JsonInt( line, sg_roundNumber );
        line += ",\"winner\":null}";
        std::vector< gBridgeConnection * > & conns = sg_Connections();
        for ( size_t i = 0; i < conns.size(); ++i )
            if ( conns[i]->roundSeen == sg_roundNumber )
                sg_Send( *conns[i], line );
    }

    sg_roundActive = true;
    sg_roundGrid = game->Grid();
    ++sg_roundNumber;
    sg_tracks.clear();
    sg_hasCandidateWinner = false;
    sg_candidateWinner = "";
    sg_maxAliveTeams = 0;

    sg_roundJson = sg_BuildRoundJson( se_GameTime() );

    std::vector< gBridgeConnection * > & conns = sg_Connections();
    for ( size_t i = 0; i < conns.size(); ++i )
    {
        sg_Send( *conns[i], sg_roundJson );
        conns[i]->needRound = false;
        conns[i]->roundSeen = sg_roundNumber;
        conns[i]->deltaKey = true; // first delta of a round replaces the host's wall set
    }

    sg_Track( sg_roundGrid );
    sg_EmitState( sg_roundGrid );
    sg_lastStateTime = tSysTimeFloat();
    sg_FlushAll();
}

void sg_BridgeRoundEnd( gGame * game )
{
    if ( !sg_roundActive )
        return;

    eGrid * grid = game ? game->Grid() : sg_roundGrid;
    sg_Track( grid );
    sg_EmitState( grid );

    std::string line = "{\"type\":\"event\",\"ev\":\"round_end\",\"round\":";
    sg_JsonInt( line, sg_roundNumber );
    line += ",\"winner\":";
    if ( sg_hasCandidateWinner )
        sg_JsonStr( line, sg_candidateWinner.c_str() );
    else
        line += "null";
    line += ",\"t\":";
    sg_JsonNum( line, se_GameTime() );
    line += "}";
    sg_SendAll( line );

    sg_roundActive = false;
    sg_roundGrid = 0;
    sg_tracks.clear();
    sg_FlushAll();
}

void sg_BridgeFrame( gGame * game )
{
    sg_BridgeIdle();

    // a new game (the previous one ended without GS_DELETE_OBJECTS, e.g. when the last
    // user left): its hosts never saw the old round, so forget it silently
    if ( sg_roundActive && game && game->GetState() < sg_GS_CREATE_OBJECTS )
    {
        sg_roundActive = false;
        sg_roundGrid = 0;
        sg_tracks.clear();
    }

    sg_ProcessInput( game );

    if ( sg_roundActive && game )
    {
        eGrid * grid = game->Grid();

        // late joiners get the current round first
        std::vector< gBridgeConnection * > & conns = sg_Connections();
        for ( size_t i = 0; i < conns.size(); ++i )
        {
            if ( conns[i]->needRound && !sg_roundJson.empty() )
            {
                sg_Send( *conns[i], sg_roundJson );
                conns[i]->needRound = false;
                conns[i]->roundSeen = sg_roundNumber;
            }
        }

        sg_Track( grid );

        double now = tSysTimeFloat();
        REAL rate = sg_bridgeRate > 0 ? sg_bridgeRate : 30;
        if ( now - sg_lastStateTime >= 1.0 / rate )
        {
            // keep a steady cadence without drifting
            sg_lastStateTime = ( now - sg_lastStateTime < 2.0 / rate ) ? sg_lastStateTime + 1.0 / rate : now;
            sg_EmitState( grid );
        }
    }

    sg_FlushAll();
    sg_ReapDead();
}
