package ru.n08i40k.guides.ui

data class GuideLabels(
    val catalogTitle: String = "Tutorials",
    val catalogDescription: String = "Learn the basics with short, interactive walkthroughs.",
    val chooseLesson: String = "Choose a walkthrough",
    val startLesson: String = "Start",
    val replayLesson: String = "Watch again",
    val locked: String = "Complete the previous tutorials first",
    val progress: (done: Int, total: Int) -> String = { done, total -> "$done of $total completed" },
    val close: String = "Close",
    val copyLink: String = "Copy link",
    val next: String = "Next",
    val done: String = "Done",
    val loadingTitle: String = "Loading profiles",
    val loadingDescription: String = "Preparing the demonstration…",
    val errorTitle: String = "Could not open tutorial",
    val errorDescription: String = "Could not prepare the demonstration. Details are in the log.",
    val profileError: (profile: String) -> String = { "Could not load $it. Details are in the log." },
)
