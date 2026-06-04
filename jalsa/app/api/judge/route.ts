import { NextRequest, NextResponse } from "next/server";
import { callClaude } from "@/lib/anthropic";
import { getRandomFallbackRuling } from "@/lib/fallbacks";
import type { JudgeRequest, JudgeResponse } from "@/lib/types";

export async function POST(req: NextRequest) {
  let firstPlayerName = "اللاعب الأول";

  try {
    const body: JudgeRequest = await req.json();
    const { prompt, answers } = body;

    if (!answers || answers.length === 0) {
      throw new Error("No answers provided");
    }

    firstPlayerName = answers[0].name;
    const playerNames = answers.map((a) => a.name);
    const answersText = answers
      .map((a, i) => `${i + 1}. ${a.name}: "${a.text}"`)
      .join("\n");

    const systemPrompt = `أنت قاضٍ ظريف وساخر في لعبة عربية مرحة. التحدّي كان:
"${prompt}"

أجوبة اللاعبين:
${answersText}

اقرأ الأجوبة واختر الفائز. اكتب حُكماً مضحكاً ودرامياً قليلاً بالعربية (٢ إلى ٤ جمل)،
أشِر فيه إلى جواب أو جوابين تحديداً بطريقة طريفة لطيفة بدون إهانة حقيقية، ثم أعلن الفائز بوضوح.
اسم الفائز يجب أن يكون مطابقاً تماماً لأحد هذه الأسماء: ${playerNames.join("، ")}
أعد ردك بصيغة JSON فقط بدون أي شرح: {"ruling":"...","winner":"الاسم"}`;

    const raw = await callClaude(systemPrompt, "احكم على الإجابات");

    const cleaned = raw
      .replace(/^```json\s*/i, "")
      .replace(/^```\s*/i, "")
      .replace(/\s*```$/i, "")
      .trim();

    const parsed = JSON.parse(cleaned) as { ruling: string; winner: string };

    const winnerRaw = parsed.winner?.trim() ?? "";
    const matchedWinner =
      playerNames.find((n) => n === winnerRaw) ??
      playerNames.find((n) => n.includes(winnerRaw) || winnerRaw.includes(n)) ??
      playerNames[0];

    const response: JudgeResponse = {
      ruling: parsed.ruling,
      winner: matchedWinner,
    };
    return NextResponse.json(response);
  } catch (err) {
    console.error("[/api/judge] error:", err);
    return NextResponse.json({
      ruling: getRandomFallbackRuling(),
      winner: firstPlayerName,
    });
  }
}
