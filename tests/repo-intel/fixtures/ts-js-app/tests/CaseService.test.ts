import { CaseService } from "../src/service/CaseService";
import { CaseStatus } from "../src/model/Case";

describe("CaseService", () => {
  it("creates and retrieves a case", async () => {
    const svc = new CaseService();
    const created = svc.createCase("1", "demo");
    expect(created.status).toBe(CaseStatus.Open);
    const found = await svc.getCase("1");
    expect(found?.title).toBe("demo");
  });

  it("exposes service name from base", () => {
    const svc = new CaseService();
    expect(svc.getName()).toBe("CaseService");
  });
});
