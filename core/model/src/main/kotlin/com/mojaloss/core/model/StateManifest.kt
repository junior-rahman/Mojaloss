package com.mojaloss.core.model

data class DesignedState(val id: String, val name: String)
object StateManifest {
    val states = listOf(
        DesignedState("D01", "Returning Home"), DesignedState("D02", "Movie details"),
        DesignedState("D03", "Browse catalog"), DesignedState("D04", "Populated My List"),
        DesignedState("A01", "Connect"), DesignedState("A02", "Reopen sign-in"),
        DesignedState("A03", "Return to title"), DesignedState("A04", "New viewer Home"),
        DesignedState("Q01", "Recent search"), DesignedState("Q02", "Keyboard search"),
        DesignedState("Q03", "Filtered results"), DesignedState("Q04", "No results"),
        DesignedState("T01", "Series seasons"), DesignedState("T02", "Episode details"),
        DesignedState("T03", "Cast"), DesignedState("T04", "Ratings"),
        DesignedState("B01", "Browse filters"), DesignedState("B02", "Empty My List"),
        DesignedState("B03", "Missing trailer"), DesignedState("B04", "Upcoming title"),
        DesignedState("P01", "Main player"), DesignedState("P02", "Audio/subtitles"),
        DesignedState("P03", "Quality"), DesignedState("P04", "Episode chooser"),
        DesignedState("S01", "Settings"), DesignedState("S02", "Account"),
        DesignedState("S03", "Playback defaults"), DesignedState("S04", "Language preferences"),
        DesignedState("U01", "Taste/appearance"), DesignedState("U02", "Privacy"),
        DesignedState("U03", "Accessibility"), DesignedState("U04", "About"),
        DesignedState("F01", "Expanded Browse"), DesignedState("F02", "Expanded player"),
        DesignedState("F03", "Tabletop"), DesignedState("R01", "Expired sign-in"),
        DesignedState("R02", "Offline Browse"), DesignedState("R03", "Failed seek"),
        DesignedState("R04", "Failed Save")
    )
}

