/** Utility helpers used by CaseService. */

export function format(value: string): string {
  return `[fmt] ${value}`;
}

export function padId(id: string): string {
  return id.padStart(8, "0");
}

export const Case = "util-case-const";

/** Exported arrow-function const (required construct). */
export const toUpper = (s: string): string => s.toUpperCase();
