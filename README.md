# Klerk-web

Klerk-web is a set of tools that helps you build a server-side rendered (SSR) web application with
[Klerk](https://klerkframework.dev/) and [Ktor](https://ktor.io).

## Installation

If you want to build a web application using Klerk, you will typically generate a Ktor project
using [the Ktor project generator](https://start.ktor.io/) or from within IntelliJ (File→New→Project).
You then add Klerk and Klerk-web to your project:

```kotlin
implementation(platform("dev.klerkframework:klerk-bom:$klerkBomVersion"))
implementation("dev.klerkframework:klerk")
implementation("dev.klerkframework:klerk-web")
```

See the [documentation](/docs/introduction.md) for details on how to use the plugin.
