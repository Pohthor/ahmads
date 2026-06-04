"use client";

import { useState } from "react";
import type { Player } from "@/lib/types";

interface Props {
  onStart: (players: Player[]) => void;
}

export default function SetupScreen({ onStart }: Props) {
  const [nameInput, setNameInput] = useState("");
  const [players, setPlayers] = useState<Player[]>([]);

  function addPlayer() {
    const trimmed = nameInput.trim();
    if (!trimmed || players.length >= 6) return;
    if (players.some((p) => p.name === trimmed)) return;
    setPlayers([...players, { id: crypto.randomUUID(), name: trimmed, score: 0 }]);
    setNameInput("");
  }

  function removePlayer(id: string) {
    setPlayers(players.filter((p) => p.id !== id));
  }

  return (
    <div className="animate-fade-in flex flex-col items-center gap-6 p-6 w-full max-w-sm mx-auto min-h-screen justify-center">
      <div className="text-center">
        <h1 className="font-lalezar text-6xl text-jalsa-coral">جَلسة</h1>
        <p className="font-lalezar text-2xl text-jalsa-amber">المحكمة</p>
        <p className="text-white/50 text-sm mt-2">لعبة الحفلات العربية</p>
      </div>

      <div className="w-full flex gap-2">
        <input
          type="text"
          value={nameInput}
          onChange={(e) => setNameInput(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && addPlayer()}
          placeholder="اسم اللاعب..."
          maxLength={20}
          autoComplete="off"
          autoCorrect="off"
          spellCheck={false}
          className="flex-1 bg-jalsa-surface border border-jalsa-border rounded-xl px-4 py-3 text-white placeholder-white/40 text-right focus:outline-none focus:border-jalsa-coral transition-colors"
        />
        <button
          onClick={addPlayer}
          disabled={!nameInput.trim() || players.length >= 6}
          className="bg-jalsa-coral text-white rounded-xl px-5 font-bold disabled:opacity-30 transition-opacity"
        >
          أضف
        </button>
      </div>

      {players.length > 0 && (
        <div className="w-full flex flex-wrap gap-2 justify-end">
          {players.map((p) => (
            <div
              key={p.id}
              className="flex items-center gap-2 bg-jalsa-surface border border-jalsa-border rounded-full px-4 py-2"
            >
              <button
                onClick={() => removePlayer(p.id)}
                className="text-white/40 hover:text-jalsa-coral transition-colors text-xl leading-none min-h-0 min-w-0"
              >
                ×
              </button>
              <span className="text-white font-medium">{p.name}</span>
            </div>
          ))}
        </div>
      )}

      <p className="text-white/40 text-sm">
        اللاعبون: {players.length} / 6
        {players.length < 2 && " — أدخل لاعبَين على الأقل"}
      </p>

      <button
        onClick={() => onStart(players)}
        disabled={players.length < 2}
        className="w-full bg-jalsa-coral text-white font-lalezar text-2xl rounded-2xl py-4 disabled:opacity-30 transition-opacity shadow-lg"
      >
        ابدأ اللعبة
      </button>
    </div>
  );
}
