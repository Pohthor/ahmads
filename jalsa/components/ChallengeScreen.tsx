"use client";

interface Props {
  prompt: string;
  isLoading: boolean;
  roundNumber: number;
  onReady: () => void;
}

export default function ChallengeScreen({
  prompt,
  isLoading,
  roundNumber,
  onReady,
}: Props) {
  return (
    <div className="animate-fade-in flex flex-col items-center gap-8 p-6 w-full max-w-sm mx-auto min-h-screen justify-center">
      <p className="text-white/40 text-sm font-medium tracking-wide">
        الجولة {roundNumber}
      </p>

      {isLoading ? (
        <div className="flex flex-col items-center gap-4">
          <div className="w-14 h-14 border-4 border-jalsa-coral/30 border-t-jalsa-coral rounded-full animate-spin-slow" />
          <p className="text-white/60 animate-pulse-opacity text-lg">
            يجهّز التحدي...
          </p>
        </div>
      ) : (
        <>
          <p className="text-jalsa-amber text-sm font-medium">تحدّي الجولة</p>

          <div className="w-full bg-jalsa-surface border-2 border-jalsa-border rounded-2xl p-6 text-center shadow-xl">
            <p className="font-lalezar text-3xl text-jalsa-coral leading-relaxed">
              «{prompt}»
            </p>
          </div>

          <button
            onClick={onReady}
            className="w-full bg-jalsa-coral text-white font-lalezar text-2xl rounded-2xl py-4 shadow-lg transition-transform active:scale-95"
          >
            جاهز! ابدأ الإجابات
          </button>
        </>
      )}
    </div>
  );
}
