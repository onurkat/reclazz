#!/usr/bin/env python3
"""Opt-in real installed SAP SDK regression; no installation mutation or native boot."""
from pathlib import Path
import argparse, os, subprocess, tempfile

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-jar", type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    sdk = Path(os.environ.get("RECLAZZ_SAP_HOME", str(repo.parent / "reclazz-sap-astra"))) / "hybris/bin"
    source = sdk / "modules/core-accelerator/acceleratorservices/src"
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    jar = args.agent_jar or repo / "agent/build/libs/agent-1.5.0.jar"
    classpath = os.pathsep.join([str(p) for p in sorted((sdk / "platform").rglob("*.jar"))] + [str(jar)])
    native = source / "de/hybris/platform/acceleratorservices/dataimport/batch"
    names = ["BatchHeader.java", "HeaderTask.java", "converter/ImpexConverter.java", "converter/ImpexRowFilter.java", "converter/impl/DefaultImpexConverter.java", "converter/mapping/ConverterMapping.java", "converter/mapping/impl/DefaultConverterMapping.java", "task/ImpexTransformerTask.java", "task/CleanupHelper.java", "util/BatchDirectoryUtils.java"]
    with tempfile.TemporaryDirectory(prefix="reclazz-hotfolder-sdk-") as temporary:
        out = Path(temporary)
        logging = out / "log4j2.xml"
        logging.write_text('<Configuration><Appenders/><Loggers><Root level="off"/></Loggers></Configuration>')
        subprocess.run([str(java / "javac"), "-proc:none", "-cp", classpath, "-sourcepath", str(source), "-d", str(out), *[str(native / n) for n in names], str(repo / "integration-test/src/sapSdkTest/hotfolder/com/example/XmlHotFolderApp.java")], check=True)
        subprocess.run([str(java / "java"), "-Dlog4j.configurationFactory=org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory", "-Dlog4j.configurationFile=" + str(logging), "-cp", str(out) + os.pathsep + classpath, "com.example.XmlHotFolderApp", str(out / "files")], check=True)

if __name__ == "__main__":
    main()
