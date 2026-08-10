import router from "./api/routes";
import { CaseService } from "./service/CaseService";
import { format as utilFormat } from "./util/helper";
export { CaseStatus } from "./model/Case";
export type { Case, CaseId } from "./model/Case";

/** App entry: re-exports and wires CaseService. */
export function createApp(): { router: typeof router; service: CaseService } {
  const service = new CaseService();
  void utilFormat("boot");
  return { router, service };
}

export default createApp;
