# armacraft MC runtime image: PROPOSAL, NOT BUILT OR DEPLOYED. See deploy/README.md.
# A plain Fabric dedicated server with pinned versions. Mods are mounted at /data/mods, and the world
# and config live in /data. The image never writes eula.txt; the server will not start until Jamie
# puts eula=true in /data/eula.txt on the target host.
FROM eclipse-temurin:25-jre
ARG MC_VERSION=26.3
ARG LOADER_VERSION=0.19.5
ARG INSTALLER_VERSION=1.1.2
ADD https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}/${LOADER_VERSION}/${INSTALLER_VERSION}/server/jar /opt/fabric/fabric-server-launch.jar
RUN useradd --system --home /data --uid 1500 mc && mkdir -p /data && chown mc /data && chmod 644 /opt/fabric/fabric-server-launch.jar
USER mc
WORKDIR /data
EXPOSE 25565/tcp
# the launcher downloads the vanilla server jar and libraries into /data on first start (kept in the volume)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/fabric/fabric-server-launch.jar nogui"]
