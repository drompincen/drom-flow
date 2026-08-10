import type { Case } from "../model/Case";
import { CaseStatus } from "../model/Case";

const store = new Map<string, Case>();

export function findById(id: string): Case | undefined {
  return store.get(id);
}

export function save(item: Case): Case {
  store.set(item.id, item);
  return item;
}

export function create(id: string, title: string): Case {
  const item: Case = { id, title, status: CaseStatus.Open };
  return save(item);
}

export async function findByIdAsync(id: string): Promise<Case | undefined> {
  return findById(id);
}
