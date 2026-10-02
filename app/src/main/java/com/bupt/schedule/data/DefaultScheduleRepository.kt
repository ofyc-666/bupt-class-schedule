package com.bupt.schedule.data

import com.bupt.schedule.data.local.ScheduleStore
import com.bupt.schedule.data.remote.SjdScheduleClient
import com.bupt.schedule.domain.logic.SemesterLogic
import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.ScheduleSnapshot
import com.bupt.schedule.domain.repository.ScheduleRepository

class DefaultScheduleRepository(
    private val client: SjdScheduleClient,
    private val store: ScheduleStore,
) : ScheduleRepository {
    override fun loadCached(): ScheduleSnapshot? {
        val cached = runCatching(store::load).getOrElse {
            store.clear()
            return null
        } ?: return null
        return cached.takeIf(SemesterLogic::canUseCached).also {
            if (it == null) store.clear()
        }
    }

    override fun refresh(credentials: Credentials): ScheduleSnapshot {
        val snapshot = client.fetch(credentials)
        // 缓存是启动优化而非真实课表成功链路的前置条件。
        runCatching { store.save(snapshot) }
        return snapshot
    }
}
