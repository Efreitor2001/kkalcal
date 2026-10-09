# App data uses explicit JSONObject keys, not reflection-based model serialization.
# No application-wide keep rules are required for diary JSON or saved recipe drafts.
#
# WorkManager supplies consumer rules preserving ListenableWorker subclass names
# and public constructors, including CloudSyncWorker. AppAuth activities are manifest
# entry points; its session JSON is encoded explicitly by the library.
#
# Yandex Mobile Ads / AppMetrica consumer rules are intentionally left intact.
# Keep any future reflection rule scoped to the concrete reflected entry point.
