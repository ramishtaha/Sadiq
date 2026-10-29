package com.ramish.sadiq.testing

import android.view.accessibility.AccessibilityNodeInfo
import com.ramish.sadiq.contract.ContentInspector
import com.ramish.sadiq.contract.InspectResult

class FakeContentInspector(
    override val packages: Set<String>,
    private val result: (AccessibilityNodeInfo, String) -> InspectResult? = { _, _ -> null },
) : ContentInspector {
    var inspectCount = 0

    override fun inspect(root: AccessibilityNodeInfo, packageName: String): InspectResult? {
        inspectCount++
        return result(root, packageName)
    }
}
