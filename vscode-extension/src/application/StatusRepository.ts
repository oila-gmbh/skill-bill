import { SkillBillStatusOutcome } from "../domain/SkillBillStatusOutcome";

export interface StatusRepository {
  fetchStatus(projectRoot: string): Promise<SkillBillStatusOutcome>;
}
