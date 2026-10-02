package com.bupt.schedule.domain.repository

import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.ScheduleSnapshot

/** 网络与本地存储的领域边界。调用方负责在后台线程执行 refresh。 */
interface ScheduleRepository {
    fun loadCached(): ScheduleSnapshot?
    fun refresh(credentials: Credentials): ScheduleSnapshot
}
