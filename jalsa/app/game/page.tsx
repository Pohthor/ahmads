"use client";

import { useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import type { GameState, Player, Round } from "@/lib/types";
import SetupScreen from "@/components/SetupScreen";
import ChallengeScreen from "@/components/ChallengeScreen";
import PassScreen from "@/components/PassScreen";
import AnswerScreen from "@/components/AnswerScreen";
import JudgingScreen from "@/components/JudgingScreen";
import VerdictScreen from "@/components/VerdictScreen";
import ScoresScreen from "@/components/ScoresScreen";

const initialState: GameState = {
  phase: "setup",
  players: [],
  rounds: [],
  currentRound: null,
  currentPlayerIndex: 0,
  recentPrompts: [],
};

const variants = {
  initial: { opacity: 0, y: 20 },
  animate: { opacity: 1, y: 0 },
  exit:    { opacity: 0, y: -20 },
};

async function fetchChallenge(recentPrompts: string[]): Promise<string> {
  const res = await fetch("/api/challenge", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ recentPrompts }),
  });
  const data = await res.json();
  return data.prompt as string;
}

async function fetchJudge(
  prompt: string,
  answers: { name: string; text: string }[]
): Promise<{ ruling: string; winner: string }> {
  const res = await fetch("/api/judge", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ prompt, answers }),
  });
  return res.json();
}

export default function GamePage() {
  const [state, setState] = useState<GameState>(initialState);

  async function handleStartGame(players: Player[]) {
    setState((s) => ({
      ...s,
      phase: "challenge",
      players,
      rounds: [],
      currentRound: null,
      currentPlayerIndex: 0,
      recentPrompts: [],
    }));
    const prompt = await fetchChallenge([]);
    setState((s) => ({
      ...s,
      currentRound: {
        roundNumber: 1,
        prompt,
        answers: [],
        winner: null,
        ruling: null,
      },
    }));
  }

  function handleChallengeReady() {
    setState((s) => ({ ...s, phase: "pass", currentPlayerIndex: 0 }));
  }

  function handlePassConfirmed() {
    setState((s) => ({ ...s, phase: "answer" }));
  }

  async function handleAnswerSubmit(text: string) {
    const currentPlayer = state.players[state.currentPlayerIndex];
    const currentPrompt = state.currentRound?.prompt ?? "";

    const newAnswer = {
      playerId: currentPlayer.id,
      playerName: currentPlayer.name,
      text,
    };

    const updatedAnswers = [...(state.currentRound?.answers ?? []), newAnswer];
    const nextIndex = state.currentPlayerIndex + 1;

    if (nextIndex < state.players.length) {
      setState((s) => ({
        ...s,
        phase: "pass",
        currentPlayerIndex: nextIndex,
        currentRound: s.currentRound
          ? { ...s.currentRound, answers: updatedAnswers }
          : null,
      }));
      return;
    }

    setState((s) => ({
      ...s,
      phase: "judging",
      currentRound: s.currentRound
        ? { ...s.currentRound, answers: updatedAnswers }
        : null,
    }));

    const judgeAnswers = updatedAnswers.map((a) => ({
      name: a.playerName,
      text: a.text,
    }));
    const { ruling, winner } = await fetchJudge(currentPrompt, judgeAnswers);

    setState((s) => {
      const updatedPlayers = s.players.map((p) =>
        p.name === winner ? { ...p, score: p.score + 1 } : p
      );
      const finishedRound: Round = {
        ...(s.currentRound!),
        answers: updatedAnswers,
        winner,
        ruling,
      };
      return {
        ...s,
        phase: "verdict",
        players: updatedPlayers,
        rounds: [...s.rounds, finishedRound],
        currentRound: finishedRound,
        recentPrompts: [...s.recentPrompts, currentPrompt].slice(-5),
      };
    });
  }

  async function handleNewRound() {
    const recentPrompts = state.recentPrompts;
    setState((s) => ({ ...s, phase: "challenge", currentRound: null }));
    const prompt = await fetchChallenge(recentPrompts);
    setState((s) => ({
      ...s,
      currentRound: {
        roundNumber: s.rounds.length + 1,
        prompt,
        answers: [],
        winner: null,
        ruling: null,
      },
    }));
  }

  function handleNewGame() {
    setState(initialState);
  }

  const currentPlayer = state.players[state.currentPlayerIndex];

  return (
    <main className="min-h-screen bg-jalsa-bg">
      <AnimatePresence mode="wait">
        <motion.div
          key={state.phase}
          variants={variants}
          initial="initial"
          animate="animate"
          exit="exit"
          transition={{ duration: 0.3, ease: "easeInOut" }}
          className="w-full"
        >
          {state.phase === "setup" && (
            <SetupScreen onStart={handleStartGame} />
          )}

          {state.phase === "challenge" && (
            <ChallengeScreen
              prompt={state.currentRound?.prompt ?? ""}
              isLoading={!state.currentRound?.prompt}
              roundNumber={state.rounds.length + 1}
              onReady={handleChallengeReady}
            />
          )}

          {state.phase === "pass" && currentPlayer && (
            <PassScreen
              playerName={currentPlayer.name}
              onConfirm={handlePassConfirmed}
            />
          )}

          {state.phase === "answer" && currentPlayer && (
            <AnswerScreen
              prompt={state.currentRound?.prompt ?? ""}
              playerName={currentPlayer.name}
              onSubmit={handleAnswerSubmit}
            />
          )}

          {state.phase === "judging" && <JudgingScreen />}

          {state.phase === "verdict" && state.currentRound && (
            <VerdictScreen
              winner={state.currentRound.winner ?? ""}
              ruling={state.currentRound.ruling ?? ""}
              onNewRound={handleNewRound}
              onScores={() => setState((s) => ({ ...s, phase: "scores" }))}
            />
          )}

          {state.phase === "scores" && (
            <ScoresScreen
              players={[...state.players].sort((a, b) => b.score - a.score)}
              onNewRound={handleNewRound}
              onNewGame={handleNewGame}
            />
          )}
        </motion.div>
      </AnimatePresence>
    </main>
  );
}
