# Introduction

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

## Building with Klerk

Assuming you already have a Klerk configuration, the tools and building blocks in klerk-web can be used to quickly
turn the configuration into a fully functional web application.

While it is possible to tweak and configure the building blocks, it is likely that you
at some point will replace some or all parts with custom code to better fit your needs.

It is recommended to use [HTML DSL](https://ktor.io/docs/server-html-dsl.html) to produce the HTML.

### Ask Klerk

One feature of Klerk is that it is easy to ask for the configuration of the application. This can be used to
keep the UI in sync with the configuration. These methods are available when you read:
* getPossibleVoidEvents: Given a model class, it will tell you which event(s) can be used to create a new instance.
* getPossibleEvents: Give it a ModelID and Klerk will figure out all events that can be applied to it considering the current state.
  You can use these methods e.g. to figure out if a certain button should be visible or not.

An even more powerful approach is to combine these methods with other building blocks to create a UI that automatically
follows the configuration. So if the Klerk configuration changes, your UI will automatically update. Example:

```kotlin
klerk.readSuspend(context) {
    call.respondHtml {
        body {
            h1 { +"Actions for ${model.props.name}" }
            getPossibleEvents(model.id).forEach {
                apply(LowCodeCreateEvent.renderButton(it, klerk, model.id, lowCodeConfig, buttonTargets, context))
            }
        }
    }
}
```

### Appearance

It is recommended to start with a classless CSS. When you are ready to add some styling, you can provide your own
class provider function to customize the CSS classes applied to form elements.
