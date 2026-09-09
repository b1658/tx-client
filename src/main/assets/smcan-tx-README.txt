smcan-tx.dex is built by tools/build-tx-dex.sh from tools/tx/src/.../SmCanTx.java. Not committed.

It lives here, in the library, so every consumer of :tx-client merges the same dex into its APK --
CanTx pushes it to /data/local/tmp and runs it as root. Rebuild it whenever SmCanTx.java changes.
