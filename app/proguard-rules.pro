# Sadiq: no shrinking in debug. Release keeps the AccessiblityService, the FGS and the widget
# receivers reachable from the manifest only, so keep those entry points.
-keep class com.ramish.sadiq.guard.service.** { *; }
-keep class com.ramish.sadiq.session.** { *; }
-keep class com.ramish.sadiq.widget.** { *; }
-keep class com.ramish.sadiq.intercept.InterceptActivity { *; }
