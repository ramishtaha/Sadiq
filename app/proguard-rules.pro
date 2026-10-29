# Saduq: no shrinking in debug. Release keeps the AccessiblityService, the FGS and the widget
# receivers reachable from the manifest only, so keep those entry points.
-keep class com.ramish.saduq.guard.service.** { *; }
-keep class com.ramish.saduq.session.** { *; }
-keep class com.ramish.saduq.widget.** { *; }
-keep class com.ramish.saduq.intercept.InterceptActivity { *; }
