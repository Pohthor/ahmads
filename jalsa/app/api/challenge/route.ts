import { NextRequest, NextResponse } from "next/server";
import { callClaude } from "@/lib/anthropic";
import { getRandomFallbackChallenge } from "@/lib/fallbacks";
import type { ChallengeRequest, ChallengeResponse } from "@/lib/types";

const CHALLENGE_STYLES = [
  "دافِع عن موقف غريب أو مستحيل بشكل مقنع",
  "اقنع القاضي بشيء يصعب تصديقه",
  "اختلق أفضل عذر مضحك لموقف محرج",
  "اختلق أكثر كذبة مقنعة عن نفسك",
  "ناظِر: مع أو ضد عادة يومية شائعة",
  "لو خيّروك بين شيئين، دافع عن خيارك",
];

export async function POST(req: NextRequest) {
  try {
    const body: ChallengeRequest = await req.json();
    const { recentPrompts = [], style } = body;

    const resolvedStyle =
      style ||
      CHALLENGE_STYLES[Math.floor(Math.random() * CHALLENGE_STYLES.length)];

    const recentList =
      recentPrompts.length > 0 ? recentPrompts.join("، ") : "لا يوجد";

    const systemPrompt = `أنت مقدّم لعبة عربية مرحة. اكتب تحدّياً واحداً قصيراً ومضحكاً للاعبين، من هذا النوع: "${resolvedStyle}".
التحدّي جملة واحدة واضحة موجّهة للاعبين (صيغة أمر للجمع)، خفيف الظل وغير مسيء،
يصلح لأي جلسة عائلية أو بين أصدقاء.
تجنّب أن يشبه أياً من هذه: ${recentList}
أعد ردك بصيغة JSON فقط بدون أي شرح: {"prompt":"..."}`;

    const raw = await callClaude(systemPrompt, "أعطني التحدي");

    const cleaned = raw
      .replace(/^```json\s*/i, "")
      .replace(/^```\s*/i, "")
      .replace(/\s*```$/i, "")
      .trim();

    const parsed = JSON.parse(cleaned) as { prompt: string };

    if (!parsed.prompt || typeof parsed.prompt !== "string") {
      throw new Error("Invalid JSON shape");
    }

    const response: ChallengeResponse = { prompt: parsed.prompt };
    return NextResponse.json(response);
  } catch (err) {
    console.error("[/api/challenge] error:", err);
    return NextResponse.json({ prompt: getRandomFallbackChallenge() });
  }
}
