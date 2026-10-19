package com.oppmesh.dtn

import android.app.Application
import com.oppmesh.dtn.data.DtnRepository
import com.oppmesh.dtn.data.nearby.NearbyMeshManager

class OppMeshApplication : Application() {

    lateinit var repository: DtnRepository
        private set

    lateinit var meshManager: NearbyMeshManager
        private set

    lateinit var identityManager: com.oppmesh.dtn.util.NodeIdentityManager
        private set

    override fun onCreate() {
        super.onCreate()
        identityManager = com.oppmesh.dtn.util.NodeIdentityManager(this)
        repository = DtnRepository.getInstance(this)
        val nodeId = identityManager.getOrCreateNodeId()
        meshManager = NearbyMeshManager(this, repository, nodeId)
    }
}
