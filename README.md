# Cosmos OS for Rider

JetBrains Rider plugin for [Cosmos gen3](https://github.com/valentinbreiz/nativeaot-patcher), the NativeAOT-based C# kernel framework. Create, build, run and debug a kernel without leaving the IDE.

## Features

Available from the **Cosmos** menu, the **Cosmos** tool window and the Run/Debug toolbar:

- New kernel project from the `cosmos new` template
- Build the kernel and run it in QEMU through `cosmos run`
- Debug with GDB: C# breakpoints, stepping, call stack, variables, watches and evaluation
- Live kernel diagnostics while debugging: threads, GC and memory (with a page map)
- Edit project properties: kernel features, QEMU machine, devices (network card, keyboard, mouse, audio), disks and port forwards
- Run the Cosmos kernel test suites (in the Cosmos repository)
- Check and install the toolchain (.NET 10 SDK, Cosmos CLI, QEMU, GDB)
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

## Running and debugging

Opening a Cosmos project adds a **Cosmos Kernel** run configuration, so the toolbar's Run and Debug buttons boot the kernel in QEMU. Run streams the serial console into the Run tool window; Debug attaches GDB to QEMU's gdbstub:

- Breakpoints are Rider's own C# breakpoints, set in the gutter as usual (conditions are passed to GDB as GDB expressions).
- Variables, Watches and Evaluate use GDB expressions; the NativeAOT pretty-printers render strings and arrays when GDB has Python.
- The debug session gets **Kernel Threads**, **Kernel GC** and **Kernel Memory** tabs, read over QEMU's QMP socket without pausing the guest.

Stop ends QEMU and GDB together. QEMU's devices, memory and disks come from **Project Properties**.

## Testing

In the Cosmos repository, the tool window's **Tests** tab lists the suites under `tests/Kernels`. Run them on x64 or arm64 (results land in the test runner tree), or debug a suite's kernel under GDB. **Cosmos Kernel Tests** run configurations can also be created by hand.

## Building from source

```bash
./gradlew runIde        # sandbox Rider with the plugin loaded
./gradlew buildPlugin   # build/distributions/*.zip
./gradlew test          # unit tests
```

`DebuggerSmokeTest` boots a real kernel under QEMU and GDB; it runs when `COSMOS_SMOKE_ROOT` points at a nativeaot-patcher checkout with the Timer test kernel built.

## Documentation

[User Guide](https://valentinbreiz.github.io/nativeaot-patcher/articles/user/install.html) — installation, kernel startup, filesystem, network, graphics and debugging.

Also available for [VS Code](https://github.com/CosmosOS/CosmosVsCodeExtension).

## License

MIT
