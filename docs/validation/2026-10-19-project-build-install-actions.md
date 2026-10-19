# Project build and install actions

Date: 2026-10-19.

Both T3 project actions invoke `scripts/build.ps1 -ModsDirectory <selected mods directory>`. The existing `gradlew.bat test build` pipeline must succeed before publishing the distribution and installing it. The default target remains `C:/Users/hung1/curseforge/minecraft/Instances/Packsage/mods`; the ATM10 action selects `C:/Users/hung1/curseforge/minecraft/Instances/All the Mods 10 - ATM10/mods`.

Each action checks the selected instance before building. The build script checks again immediately before installation, copies only to the selected target and verifies its SHA-256 against the built distribution. A failed Gradle invocation throws before distribution or installation.

Three isolated checks passed using the actual PowerShell build script and portable Java 21, with a stub Gradle command and temporary targets:

- The wrapper requests `test build`, then installs and verifies the selected minimal target.
- Selecting ATM10 installs the new artifact there and preserves the minimal target's previous artifact.
- A Gradle failure exits before installation and preserves the existing target JAR.

These checks validate wrapper sequencing, target selection and failure handling. They do not represent a new run of the Java unit suite or an installation into a live Minecraft profile. The two actions are saved in this environment's PackSage project settings as **Build & Install Minimal Fixture** and **Build & Install ATM10**.
