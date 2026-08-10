/**
 * SAME basename as util/helper.ts — deliberate trap.
 * CaseService must NOT resolve format() to this file.
 */

export function format(value: string): string {
  return `[other] ${value}`;
}

export function unusedOther(): string {
  return "other";
}
