import "server-only";
import Anthropic from "@anthropic-ai/sdk";

const client = new Anthropic({
  apiKey: process.env.ANTHROPIC_API_KEY,
});

const MODEL = "claude-sonnet-4-20250514";
const MAX_TOKENS = 1000;

export async function callClaude(system: string, user: string): Promise<string> {
  const message = await client.messages.create({
    model: MODEL,
    max_tokens: MAX_TOKENS,
    system,
    messages: [{ role: "user", content: user }],
  });

  const block = message.content[0];
  if (block.type !== "text") throw new Error("Unexpected content type");
  return block.text;
}
