import express from "express";
import { CaseService } from "../service/CaseService";

const router = express.Router();
const caseService = new CaseService();

router.get("/cases/:id", async (req, res) => {
  const item = await caseService.getCase(req.params.id);
  res.json(item ?? null);
});

router.post("/cases", (req, res) => {
  const { id, title } = req.body;
  const created = caseService.createCase(id, title);
  res.status(201).json(created);
});

export default router;
export { router };
