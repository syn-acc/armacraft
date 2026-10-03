/*
 * armacraft bridge: streams the Armagetron simulation as newline-delimited JSON
 * over TCP to a host (Minecraft server mod) and accepts input back.
 * Protocol: armacraft PROTOCOL.md v1.
 *
 * Hooks into upstream (see guest/patches):
 *   gGame::NetSyncIdle()      -> sg_BridgeIdle()        (I/O only: accept/read/write)
 *   sg_EnterGameCore loop     -> sg_BridgeFrame( game ) (commands, events, state)
 *   GS_CREATE_OBJECTS         -> sg_BridgeRoundStart( game )
 *   GS_DELETE_OBJECTS         -> sg_BridgeRoundEnd( game )
 *   sg_NumUsers/sg_NumHumans  -> sg_BridgeNumUsers()
 */

#ifndef ArmageTron_gBRIDGE_H
#define ArmageTron_gBRIDGE_H

class gGame;

//! network I/O only (accept, read into buffers, flush). Safe to call from any wait loop.
void sg_BridgeIdle();

//! once per main loop frame while a game exists: run host commands, emit events and state.
void sg_BridgeFrame( gGame * game );

//! called right after the round's objects (cycles) were created
void sg_BridgeRoundStart( gGame * game );

//! called right before the round's objects get deleted
void sg_BridgeRoundEnd( gGame * game );

//! number of connected bridge hosts; they count as users so the dedicated server does not nap
int sg_BridgeNumUsers();

#endif
