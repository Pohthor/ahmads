"use client";

import { useState } from "react";

interface Props {
  prompt: string;
  playerName: string;
  onSubmit: (answer: string) => void;
}

export default function AnswerScreen({ prompt, playerName, onSubmit }: Props) {
  const [answer, setAnswer] = useState("");

  return (
    <div className="animate-fade-in flex flex-col gap-5 p-6 w-full max-w-sm mx-auto min-h-screen justify-center">
      <p className="text-center font-lalezar text-3xl text-jalsa-coral">
        {playerName}، دورك!
      </p>

      <div className="bg-jalsa-surface border border-jalsa-border rounded-xl p-4 text-center">
        <p className="text-jalsa-amber text-xs mb-2 font-medium">التحدي</p>
        <p className="text-white leading-relaxed">«{prompt}»</p>
      </div>

      <textarea
        value={answer}
        onChange={(e) => setAnswer(e.target.value.slice(0, 300))}
        placeholder="اكتب إجابتك هنا..."
        autoComplete="off"
        autoCorrect="off"
        spellCheck={false}
        className="w-full bg-jalsa-surface border border-jalsa-border rounded-xl p-4 text-white placeholder-white/40 text-lg min-h-[160px] resize-none focus:outline-none focus:border-jalsa-coral transition-colors"
      />

      <p className="text-white/30 text-xs text-left">{answer.length} / 300</p>

      <button
        onClick={() => onSubmit(answer.trim())}
        disabled={!answer.trim()}
        className="w-full bg-jalsa-coral text-white font-lalezar text-2xl rounded-2xl py-4 disabled:opacity-30 transition-opacity shadow-lg active:scale-95"
      >
        أرسل جوابي
      </button>
    </div>
  );
}
