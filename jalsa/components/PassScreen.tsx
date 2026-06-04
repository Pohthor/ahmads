"use client";

interface Props {
  playerName: string;
  onConfirm: () => void;
}

export default function PassScreen({ playerName, onConfirm }: Props) {
  return (
    <div className="animate-fade-in flex flex-col items-center gap-8 p-6 w-full max-w-sm mx-auto min-h-screen justify-center">
      <div className="text-7xl select-none">📱</div>

      <div className="text-center">
        <p className="text-white/60 text-xl mb-3">مرّر الجهاز إلى</p>
        <p className="font-lalezar text-5xl text-jalsa-coral">{playerName}</p>
      </div>

      <button
        onClick={onConfirm}
        className="w-full bg-jalsa-mint text-white font-lalezar text-2xl rounded-2xl py-4 shadow-lg transition-transform active:scale-95"
      >
        ✓ تم التسليم
      </button>
    </div>
  );
}
