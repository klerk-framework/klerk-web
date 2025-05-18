# Klerk-web

Klerk-web is a set of tools that helps you build a server-side rendered (SSR) web application with
[Klerk](https://klerkframework.dev/). It should be used together with [Ktor](https://ktor.io).

# Installation

Typically, you will generate a Ktor project using [the Ktor project generator](https://start.ktor.io/) or from within
IntelliJ (File→New→Project). You then add Klerk and Klerk-web to your project:

```kotlin
implementation("com.github.klerk-framework:klerk:$klerk_version")
implementation("com.github.klerk-framework:klerk-web:$klerk_web_version") 
```

See the [Klerk documentation](https://klerkframework.dev/) for details on how to use the plugin.
