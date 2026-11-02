package com.vlad.mcpserver.mcp

internal object ActionEligibility {
    fun failures(
        enabled: Boolean, accessibilityVisible: Boolean, hasVisibleBounds: Boolean,
        fullBounds: Boolean, reportedClipping: Boolean, policy: String
    ): List<String> = buildList {
        if (!enabled) add("disabled")
        if (!accessibilityVisible) add("not_accessibility_visible")
        if (!hasVisibleBounds) add("outside_visible_bounds")
        else if (policy == "fully_visible") {
            if (!fullBounds) add("clipped_by_ancestor_or_viewport")
            if (reportedClipping) add("reported_clipping")
        }
    }
}
