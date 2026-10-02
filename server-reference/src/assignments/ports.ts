import type { UCloudFetchResult, UCloudSession } from '../ucloud/client';

/** A source of UCloud task snapshots; tests and deployments may provide their own implementation. */
export interface AssignmentSource {
  login(account: string, password: string): Promise<UCloudSession>;
  fetchAll(session: UCloudSession): Promise<UCloudFetchResult>;
}
