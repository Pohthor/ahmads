export type GamePhase =
  | "setup"
  | "challenge"
  | "pass"
  | "answer"
  | "judging"
  | "verdict"
  | "scores";

export interface Player {
  id: string;
  name: string;
  score: number;
}

export interface Answer {
  playerId: string;
  playerName: string;
  text: string;
}

export interface Round {
  roundNumber: number;
  prompt: string;
  answers: Answer[];
  winner: string | null;
  ruling: string | null;
}

export interface GameState {
  phase: GamePhase;
  players: Player[];
  rounds: Round[];
  currentRound: Round | null;
  currentPlayerIndex: number;
  recentPrompts: string[];
}

export interface ChallengeRequest {
  recentPrompts: string[];
  style?: string;
}

export interface ChallengeResponse {
  prompt: string;
}

export interface JudgeRequest {
  prompt: string;
  answers: { name: string; text: string }[];
}

export interface JudgeResponse {
  ruling: string;
  winner: string;
}
