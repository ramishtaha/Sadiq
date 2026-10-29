package com.ramish.sadiq.core.testing

import com.ramish.sadiq.core.insight.ReflectionPolicy

/**
 * A [ReflectionPolicy] whose two answers are set by the test.
 *
 * [shouldReflect] still honours `enabled`, because a fake that ignored it would let an engine test
 * "prove" a check-in appears while the user has switched check-ins off.
 *
 * @property opens the answer to [opensInWindow].
 * @property reflect whether a check-in should appear when check-ins are enabled.
 */
class FakeReflectionPolicy(
    var opens: Int = 0,
    var reflect: Boolean = false
) : ReflectionPolicy {

    /** Method names, in call order, for interaction assertions. */
    val calls: MutableList<String> = mutableListOf()

    /** The opens list the engine last passed in. */
    var lastOpensWall: List<Long>? = null

    /** The cooldown reference the engine last passed in. */
    var lastReflectWall: Long? = null

    /** The wall time the engine last asked about. */
    var lastNowWall: Long? = null

    /** The enabled flag the engine last passed in. */
    var lastEnabled: Boolean? = null

    override fun opensInWindow(opensWall: List<Long>, nowWall: Long): Int {
        calls += "opensInWindow"
        lastOpensWall = opensWall
        lastNowWall = nowWall
        return opens
    }

    override fun shouldReflect(
        opensWall: List<Long>,
        lastReflectWall: Long?,
        nowWall: Long,
        enabled: Boolean
    ): Boolean {
        calls += "shouldReflect"
        lastOpensWall = opensWall
        this.lastReflectWall = lastReflectWall
        lastNowWall = nowWall
        lastEnabled = enabled
        return reflect && enabled
    }
}
