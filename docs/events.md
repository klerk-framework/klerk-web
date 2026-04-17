# Events

It is possible to automatically render a button for each possible event. When the user clicks the button, a form is 
rendered and the user submits the form, the event is triggered. 

```kotlin
getPossibleEvents(model.id).forEach {
    apply(LowCodeCreateEvent.renderButton(it, klerk, model.id, lowCodeConfig, buttonTargets, ctx))
}
```

In order for this to work, the Admin-UI must have been configured.
