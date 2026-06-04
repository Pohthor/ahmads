"use client";

interface Props {
  winner: string;
  ruling: string;
  onNewRound: () => void;
  onScores: () => void;
}

export default function VerdictScreen({
  winner,
  ruling,
  onNewRound,
  onScores,
}: Props) {
  return (
    <div className="animate-fade-in flex flex-col items-center gap-8 p-6 w-full max-w-sm mx-auto min-h-screen justify-center">
      <div className="text-center">
        <div className="text-7xl animate-crown mb-4 select-none">👑</div>
        <p className="text-white/60 text-lg mb-2">فاز بهذه الجولة</p>
        <p className="font-lalezar text-5xl text-jalsa-amber">{winner}</p>
      </div>

      <div className="w-full bg-jalsa-surface border-2 border-jalsa-border rounded-2xl p-6 shadow-xl">
        <p className="text-jalsa-coral text-xs font-medium mb-3 text-center tracking-wide">
          ⚖️ حُكم القاضي
        </p>
        <p className="text-white/90 leading-relaxed text-center">
          «{ruling}»
        </p>
      </div>

      <div className="w-full flex gap-3">
        <button
          onClick={onNewRound}
          className="flex-1 bg-jalsa-coral text-white font-lalezar text-xl rounded-2xl py-4 shadow-lg transition-transform active:scale-95"
        >
          جولة جديدة
        </button>
        <button
          onClick={onScores}
          className="flex-1 border-2 border-jalsa-amber text-jalsa-amber font-lalezar text-xl rounded-2xl py-4 transition-transform active:scale-95"
        >
          النقاط
        </button>
      </div>
    </div>
  );
}
