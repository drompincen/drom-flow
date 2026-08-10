import { BaseService } from "./BaseService";
import * as repo from "../repo/caseRepo";
import { format, padId } from "../util/helper";
import type { Case } from "../model/Case";
import { CaseStatus } from "../model/Case";

/**
 * CaseService extends BaseService and calls caseRepo + util/helper.
 * Deliberate traps: other/helper.format, any-typed method, comment/template calls.
 */
export class CaseService extends BaseService {
  constructor() {
    super("CaseService");
  }

  async getCase(id: string): Promise<Case | undefined> {
    this.log(format(padId(id)));
    // format() here resolves to util/helper, NOT other/helper
    return repo.findByIdAsync(id);
  }

  createCase(id: string, title: string): Case {
    const created = repo.create(id, title);
    return repo.save({ ...created, status: CaseStatus.Open });
  }

  /** Method that a naive extractor might link from `any` callers — trap target. */
  ghostMethod(): string {
    return "ghost";
  }

  trapAnyCall(dyn: any): void {
    // call on `any` — must NOT resolve to CaseService.ghostMethod or repo methods
    dyn.ghostMethod();
    dyn.findById("x");
  }

  commentAndTemplateTraps(): void {
    // repo.findById("comment-trap")
    /* repo.save({ id: "block", title: "t", status: CaseStatus.Open }) */
    const _s = `never call repo.findByIdAsync("${"tpl"}")`;
    void _s;
  }
}
