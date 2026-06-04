"use client";

import type { Player } from "@/lib/types";

interface Props {
  players: Player[];
  onNewRound: () => void;
  onNewGame: () => void;
}

export default function ScoresScreen({ players, onNewRound, onNewGame }: Props) {
  return (
    <div className="animate-fade-in flex flex-col gap-6 p-6 w-full max-w-sm mx-auto min-h-screen justify-center">
      <h2 className="font-lalezar text-4xl text-center text-white">
        لوحة النقاط
      </h2>

      <div className="flex flex-col gap-3">
        {players.map((player, index) => (
          <div
            key={player.id}
            className={`flex items-center gap-4 rounded-2xl px-5 py-4 ${
              index === 0
                ? "bg-jalsa-amber/20 border-2 border-jalsa-amber"
                : "bg-jalsa-surface border border-jalsa-border"
            }`}
          >
            <span
              className={`text-2xl font-lalezar w-8 text-center ${
                index === 0 ? "text-jalsa-amber" : "text-white/40"
              }`}
            >
              {index === 0 ? "👑" : index + 1}
            </span>
            <span
              className={`flex-1 font-lalezar text-xl ${
                index === 0 ? "text-jalsa-amber" : "text-white"
              }`}
            >
              {player.name}
            </span>
            <div className="flex items-baseline gap-1">
              <span
                className={`font-lalezar text-3xl ${
                  index === 0 ? "text-jalsa-amber" : "text-jalsa-coral"
                }`}
              >
                {player.score}
              </span>
              <span className="text-white/40 text-xs">نقطة</span>
            </div>
          </div>
        ))}
      </div>

      <div className="flex flex-col gap-3">
        <button
          onClick={onNewRound}
          className="w-full bg-jalsa-coral text-white font-lalezar text-2xl rounded-2xl py-4 shadow-lg transition-transform active:scale-95"
        >
          جولة جديدة
        </button>
        <button
          onClick={onNewGame}
          className="w-full border-2 border-jalsa-amber text-jalsa-amber font-lalezar text-xl rounded-2xl py-4 transition-transform active:scale-95"
        >
          جلسة جديدة
        </button>
      </div>
    </div>
  );
}
