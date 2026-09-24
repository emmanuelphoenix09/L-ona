// Proxy minimal (Node 18+). Lancer avec :
//   ANTHROPIC_API_KEY=sk-ant-... APP_TOKEN=change-moi node server.mjs
import http from "http";

const KEY = process.env.ANTHROPIC_API_KEY;
const TOKEN = process.env.APP_TOKEN;

const SYSTEM = `Tu es un assistant vocal qui pilote un téléphone Android.
Réponds en français, en une ou deux phrases courtes (tes réponses sont lues à voix haute).
Avant de toucher l'écran, appelle read_screen pour connaître les coordonnées.
Après chaque action, vérifie le résultat avec read_screen si nécessaire.
N'envoie jamais de message et n'effectue jamais de paiement sans que l'utilisateur l'ait demandé explicitement.`;

http.createServer(async (req, res) => {
  if (req.method !== "POST" || req.headers.authorization !== `Bearer ${TOKEN}`) {
    res.writeHead(401).end();
    return;
  }
  try {
    let body = "";
    for await (const chunk of req) body += chunk;
    const { messages, tools } = JSON.parse(body);

    const r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "x-api-key": KEY,
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model: "claude-sonnet-5",
        max_tokens: 1024,
        system: SYSTEM,
        messages,
        tools,
      }),
    });
    res.writeHead(r.status, { "content-type": "application/json" });
    res.end(await r.text());
  } catch (e) {
    res.writeHead(500).end(String(e));
  }
}).listen(3000, () => console.log("Proxy sur :3000"));
