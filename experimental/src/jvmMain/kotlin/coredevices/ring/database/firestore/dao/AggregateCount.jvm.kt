package coredevices.ring.database.firestore.dao

import dev.gitlive.firebase.firestore.CollectionReference

// Android/iOS use the native SDKs' server-side aggregate query; the JVM Firestore SDK exposes no
// aggregate API, so the documents have to be fetched and counted.
actual suspend fun CollectionReference.count(): Int = get().documents.size
