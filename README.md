# Cosmos OS for Rider

JetBrains Rider plugin for [Cosmos gen3](https://github.com/valentinbreiz/nativeaot-patcher), the NativeAOT-based C# kernel framework. Create, build, run and debug a kernel without leaving the IDE.

## Features

Available from the **Cosmos** menu and tool window:

- New kernel project from the `cosmos new` template
- Build the kernel and run it in QEMU
- Debug with GDB
- Check and install the toolchain (.NET 10 SDK, Cosmos CLI, QEMU, GDB)
- Edit project properties
- Clean build outputs

## Requirements

- JetBrains Rider 2025.3+
- [.NET SDK 10.0](https://dotnet.microsoft.com/download)+
- [Cosmos.Tools](https://www.nuget.org/packages/Cosmos.Tools) CLI, QEMU, and GDB (`gdb-multiarch` for ARM64)

Missing tools can be installed from the plugin.

## Installation

Install the plugin, then set up the toolchain:

```bash
dotnet tool install -g Cosmos.Tools
cosmos install
cosmos check
```

On Windows, run `CosmosSetup-<version>-windows.exe` from the [releases page](https://github.com/valentinbreiz/nativeaot-patcher/releases) instead.

## Building from source

```bash
./gradlew runIde        # sandbox Rider with the plugin loaded
./gradlew buildPlugin   # build/distributions/*.zip
```

## Documentation

[User Guide](https://valentinbreiz.github.io/nativeaot-patcher/articles/user/install.html) — installation, kernel startup, filesystem, network, graphics and debugging.

Also available for [VS Code](https://github.com/valentinbreiz/CosmosVsCodeExtension).

## License

MIT
