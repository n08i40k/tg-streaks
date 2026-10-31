package ru.n08i40k.guides.model

@DslMarker
annotation class GuideDsl

fun guideScript(title: String, build: GuideScriptBuilder.() -> Unit): GuideScript =
    GuideScriptBuilder().apply(build).build(title)

@GuideDsl
class GuideScriptBuilder internal constructor() {
    private val steps = mutableListOf<GuideStep>()

    fun step(heading: String, description: String, build: GuideStepBuilder.() -> Unit) {
        steps += GuideStepBuilder().apply(build).build(heading, description)
    }

    internal fun build(title: String): GuideScript = GuideScript(title, steps.toList())
}

@GuideDsl
class GuideStepBuilder internal constructor() {
    private val actions = mutableListOf<GuideAction>()

    operator fun GuideAction.unaryPlus() {
        actions += this
    }

    internal fun build(heading: String, description: String): GuideStep =
        GuideStep(heading, description, actions.toList())
}
