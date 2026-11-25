#!/bin/bash
# Runs inside the isolated test container.
set -euo pipefail
mkdir -p /tmp/ide /tmp/plugins /tmp/config/options /tmp/fixture/.idea/runConfigurations /tmp/driver /tmp/client
trap 'test ! -f /tmp/ide-log/idea.log || cp /tmp/ide-log/idea.log /out/idea.log' EXIT
tar -xzf /input/idea.tar.gz --strip-components=1 -C /tmp/ide
unzip -q /input/plugin.zip -d /tmp/plugins
unzip -q /input/robot.zip -d /tmp/plugins
cp -R /suite/fixture/src /tmp/fixture/
cp /suite/fixture/build.gradle.kts /tmp/fixture/
echo 'rootProject.name = "fixture"' > /tmp/fixture/settings.gradle.kts
ln -s /input/fixture-libs /tmp/fixture/lib
echo fixture > /tmp/fixture/.idea/.name
cat > /tmp/fixture/.idea/modules.xml <<'XML'
<project version="4"><component name="ProjectModuleManager"><modules><module fileurl="file://$PROJECT_DIR$/fixture.iml" filepath="$PROJECT_DIR$/fixture.iml"/></modules></component></project>
XML
cat > /tmp/fixture/.idea/misc.xml <<'XML'
<project version="4"><component name="ProjectRootManager" version="2" languageLevel="JDK_17" project-jdk-name="Fixture JDK" project-jdk-type="JavaSDK"><output url="file://$PROJECT_DIR$/out"/></component></project>
XML
cat > /tmp/fixture/fixture.iml <<'XML'
<module type="JAVA_MODULE" version="4"><component name="NewModuleRootManager" inherit-compiler-output="true">
<exclude-output/><content url="file://$MODULE_DIR$"><sourceFolder url="file://$MODULE_DIR$/src/main/java" isTestSource="false"/></content>
<orderEntry type="inheritedJdk"/><orderEntry type="sourceFolder" forTests="false"/>
<orderEntry type="module-library"><library><CLASSES><root url="file://$MODULE_DIR$/lib"/></CLASSES><JAVADOC/><SOURCES/><jarDirectory url="file://$MODULE_DIR$/lib" recursive="false"/></library></orderEntry>
</component></module>
XML
cat > /tmp/fixture/.idea/reclazz.xml <<'XML'
<project version="4"><component name="ReclazzSettings"><option name="enabled" value="true"/><option name="startupDelaySeconds" value="0"/></component></project>
XML
cat > /tmp/fixture/.idea/runConfigurations/Fixture.xml <<'XML'
<component name="ProjectRunConfigurationManager"><configuration default="false" name="Fixture" type="Application" factoryName="Application">
<option name="MAIN_CLASS_NAME" value="com.example.ui.UiApp"/><option name="VM_PARAMETERS" value="-Xmx256m -Dserver.port=18882 -Dserver.address=127.0.0.1"/>
<module name="fixture"/><method v="2"/></configuration></component>
XML

javac --release 17 -proc:none -cp '/tmp/ide/lib/*:/tmp/ide/plugins/java/lib/*:/tmp/plugins/reclazz/lib/*' -d /tmp/driver /suite/driver/IdeActions.java
cp -R /suite/driver/META-INF /tmp/driver/
mkdir -p /tmp/plugins/ui-driver/lib
jar cf /tmp/plugins/ui-driver/lib/driver.jar -C /tmp/driver .
javac --release 17 -cp '/input/client-libs/*' -d /tmp/client /suite/robot/UiClient.java

cat > /tmp/config/options/updates.xml <<'XML'
<application><component name="UpdatesConfigurable"><option name="CHECK_NEEDED" value="false"/><option name="PLUGINS_CHECK_NEEDED" value="false"/><option name="SHOW_WHATS_NEW_EDITOR" value="false"/></component></application>
XML
cp /tmp/ide/bin/idea64.vmoptions /tmp/profile.vmoptions
cat >> /tmp/profile.vmoptions <<'OPTIONS'
-Didea.config.path=/tmp/config
-Didea.system.path=/tmp/system
-Didea.plugins.path=/tmp/plugins
-Didea.log.path=/tmp/ide-log
-Drobot-server.port=8580
-Djb.privacy.policy.text=<!--999.999-->
-Djb.consents.confirmation.enabled=false
-Didea.suppress.statistics.report=true
-Didea.disable.collect.statistics=true
-Didea.trust.all.projects=true
-Dide.show.tips.on.startup.default.value=false
OPTIONS
export IDEA_VM_OPTIONS=/tmp/profile.vmoptions DISPLAY=:73
Xvfb "$DISPLAY" -screen 0 1280x900x24 -nolisten tcp > /out/xvfb.log 2>&1 &
/tmp/ide/bin/idea.sh /tmp/fixture >/out/launcher.log 2>&1 &
ide=$!
for ((n=0; n<100; n++)); do
    kill -0 "$ide" || { echo 'IDE exited before Robot startup'; exit 1; }
    if (echo >/dev/tcp/127.0.0.1/8580) 2>/dev/null; then
        timeout 12m java --add-opens=java.base/java.lang=ALL-UNNAMED -cp '/tmp/client:/input/client-libs/*' UiClient "${1:-all}" 2>&1 | tee /out/scenarios.log
        exit 0
    fi
    sleep 1
done
echo 'Robot endpoint startup timed out' >&2
exit 1
