package com.ramish.sadiq.core.web

import com.ramish.sadiq.core.engine.AppSurface

/**
 * Matches a browser URL against the guarded domains.
 *
 * A URL is never stored; only the matched domain is, and only to name the target on a surface
 * (§3.9).
 */
interface DomainMatcher {
    /**
     * Reduce a raw URL to a bare registrable host.
     *
     * @param rawUrl the address-bar text, with or without a scheme.
     * @return the lower-case host with `www.` removed, or null when it cannot be read as a URL.
     */
    fun normalize(rawUrl: String): String?

    /**
     * Find the guarded domain a URL belongs to, including subdomains.
     *
     * @param rawUrl the address-bar text.
     * @param guarded the configured domains.
     * @return the matched configured domain, or null.
     */
    fun match(rawUrl: String, guarded: Set<String>): String?
}

/** Decides which surface of an app is in front, from the node ids an inspector reports. */
interface SurfaceDetector {
    /**
     * @param packageName the app in front.
     * @param viewIds the ids present in the window's content tree.
     */
    fun detect(packageName: String, viewIds: Set<String>): AppSurface
}
