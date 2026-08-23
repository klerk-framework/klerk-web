# Admin UI

The Admin UI is an operations console for the people who run your system.

**It is an internal tool.** It is not a foundation for the UI your end users see, and it is not meant to be themed or
grown into your product. Build that from the [building blocks](introduction.md) instead.

## What it gives you

* A list and a detail page for every managed model, with a button for every possible event.
* [Audit log](#audit-log) - every event that has been executed.
* [Jobs](#jobs) - what is scheduled, running or dead-lettered, and cancel/resume/delete.
* [Documentation](#documentation) - your specification: models, state diagrams, authorization rules, plugins.
* [Log](#log) - Klerk's own log.
* Metrics - JVM memory and processors.
* [Settings](#settings) - the running instance's `KlerkSettings`, and which env var each was resolved from.
* [Plugins](plugins.md) - a page per plugin that provides one.

## Initializing

```kotlin
val adminUI = AdminUI(
    support.withPathProvider(DefaultPathProvider(prefix = "admin/")),
    canSeeAdminUI = ::canSeeAdminUI,
)

routing {
    adminUiRoutes(adminUI)
}
```

The `PathProvider` decides where the pages are mounted; with the prefix above they are at `/admin/`. Use
`support.withPathProvider` so the Admin UI shares the application's `Layout` and `CssClassProvider`.

`KlerkWeb` does all of this for you - see [Quick start](introduction.md#quick-start).

## Authorization

`canSeeAdminUI` is called on every request to an operations page. When it returns false the page answers **404**, not
403, so the existence of the Admin UI is not revealed.

It is not a second permission system. Klerk's own rules still apply to everything behind it:

* which events a user may execute (`getPossibleEvents`),
* which models a user may read,
* the audit log (`eventLogPositiveRules`),
* which jobs a user may see or control.

So `canSeeAdminUI` decides whether the console is shown at all - not what may be done in it.

> Note: the documentation page has no Klerk rules, so if you want to prevent someone from reading it, you must not allow
> `canSeeAdminUI` for them.

## Audit log

Every executed event, with its parameters and actor. Reached from a model's detail page with the "History" button,
or in full at `_audit`.

## Jobs

Lists jobs with status, priority, parent and progress, and refreshes itself while a job is running. A job's detail
page can cancel it, resume a dead-lettered one, or delete a terminal one. Each of those is authorized by Klerk's
jobs rules.

## Documentation

Renders your specification: the properties and validation rules of every model, a state diagram per state machine,
the authorization rules, the registered algorithms as flow charts, and the plugins. For a `String` property you can
try a value against its validators.

## Log

Klerk's own log: what was read and written, by whom, with the facts behind each entry.

## Settings

Lists the running instance's `KlerkSettings`. For a setting built with `KlerkSettingsBuilder` from an `EnvVar`, the
"Env var" column names the variable that controls it, so an operator knows what to change (and restart) rather than
having to read the code.
