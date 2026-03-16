const express = require('express');
const Groq = require('groq-sdk');
const path = require('path');

const app = express();

if (!process.env.GROQ_API_KEY) {
  console.error('\n❌ GROQ_API_KEY bulunamadı!');
  console.error('Lütfen şunu çalıştır:');
  console.error('  export GROQ_API_KEY="gsk_..."\n');
  process.exit(1);
}

const groq = new Groq({ apiKey: process.env.GROQ_API_KEY });

app.use(express.json());
app.use(express.static(path.join(__dirname)));

const SYSTEM_PROMPT = `Sen ÖMER AI'sın — zeki, eğlenceli ve her konuda yardımcı olan bir yapay zeka asistanısın.

## Kimsin?
Adın ÖMER AI. Öğrencilerin en iyi arkadaşı ve dijital öğretmenisin. Her derse hakimsin, en zor soruları bile çözebilirsin, soru hazırlayabilirsin ve aynı zamanda şakalaşıp eğlenebilirsin.

## Yeteneklerin:
- **Her derste uzman**: Matematik, Fizik, Kimya, Biyoloji, Tarih, Coğrafya, Edebiyat, Felsefe, İngilizce ve daha fazlası
- **Soru hazırlama**: Test soruları (A/B/C/D), klasik sorular, boşluk doldurma, doğru-yanlış, cevap anahtarıyla birlikte
- **Zor sorular**: Olimpiyat ve YKS/LGS seviyesine kadar her zorlukta soru çözebilirsin
- **Eğlenceli**: Espri ve şaka yapabilirsin, samimi ve cana yakınsın
- **Motivasyon**: Öğrencileri cesaretlendirirsin

## Kişiliğin:
Zeki ama kibirli değilsin. Eğlenceli ama ciddileşmesini de bilirsin. Yardımsever ve sabırlısın. "Ömer" ya da "Ömer AI" diye hitap edebilirler.

## Cevap formatın:
- Her zaman Türkçe konuşursun (İngilizce sorulursa İngilizce de yanıt verebilirsin)
- Matematikte Unicode semboller kullan (→, ², ³, ∑, √, π, vb.)
- Adım adım açıklarken numaralı liste kullan
- Soru hazırlarken zorluk seviyesini göster: Kolay 🟢 / Orta 🟡 / Zor 🔴
- Tablo, başlık ve madde işareti kullanabilirsin`;

app.post('/chat', async (req, res) => {
  const { messages } = req.body;

  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.setHeader('Access-Control-Allow-Origin', '*');

  try {
    const stream = await groq.chat.completions.create({
      model: 'llama-3.3-70b-versatile',
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        ...messages
      ],
      stream: true,
      max_tokens: 8192,
      temperature: 0.7
    });

    for await (const chunk of stream) {
      const text = chunk.choices[0]?.delta?.content || '';
      if (text) {
        res.write(`data: ${JSON.stringify({ text })}\n\n`);
      }
    }

    res.write('data: [DONE]\n\n');
    res.end();
  } catch (error) {
    console.error('Hata:', error.message);
    res.write(`data: ${JSON.stringify({ error: error.message })}\n\n`);
    res.end();
  }
});

const PORT = process.env.PORT || 4000;
app.listen(PORT, '0.0.0.0', () => {
  console.log(`\n✅ ÖMER AI hazır!`);
  console.log(`   Lokal:   http://localhost:${PORT}`);
  const { networkInterfaces } = require('os');
  const nets = networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === 'IPv4' && !net.internal) {
        console.log(`   Ağ:      http://${net.address}:${PORT}`);
      }
    }
  }
  console.log();
});
