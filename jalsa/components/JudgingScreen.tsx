"use client";

export default function JudgingScreen() {
  return (
    <div className="animate-fade-in flex flex-col items-center gap-8 p-6 w-full max-w-sm mx-auto min-h-screen justify-center">
      <div className="relative w-28 h-28 flex items-center justify-center">
        <div className="absolute inset-0 border-4 border-jalsa-amber/20 border-t-jalsa-amber rounded-full animate-spin-slow" />
        <span className="text-5xl">⚖️</span>
      </div>

      <div className="text-center">
        <p className="font-lalezar text-3xl text-jalsa-amber animate-pulse-opacity">
          القاضي يفكّر...
        </p>
        <p className="text-white/40 text-sm mt-2">انتظر الحُكم</p>
      </div>
    </div>
  );
}
