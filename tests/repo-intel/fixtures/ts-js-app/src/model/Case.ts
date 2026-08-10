/** Case domain model: interface, type alias, and enum. */

export enum CaseStatus {
  Open = "open",
  Closed = "closed",
  Pending = "pending",
}

export interface Case {
  id: string;
  title: string;
  status: CaseStatus;
}

export type CaseId = string;

/** Second top-level `Case` symbol lives in helper (const) — same simple name trap. */
export const CASE_DEFAULT_TITLE = "untitled";
