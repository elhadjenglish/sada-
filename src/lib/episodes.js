// Original episodes written for Sada (no third-party audio or text). Narrated by the device's English voice.
export const EPISODES = [
  {
    id: 'neighbor', title: 'The neighbor who fixes everything', category: 'Daily life', level: 'A2', minutes: 2,
    blurb: 'Karim is retired, but he is never bored.',
    text: `My neighbor Karim is a very helpful person. When my door was broken last winter, he came with his tools and fixed it in ten minutes. He did not ask for money. He only asked for a cup of tea. Karim is retired now, but he is never bored. In the morning, he waters the plants in our building. In the afternoon, he helps children with their homework. Some people say he is too busy. I think he is happy. Last week, the old lady on the third floor needed a doctor. Karim drove her to the hospital and waited with her for three hours. When I asked him why he does all this, he smiled. He said, "A good neighbor is closer than a brother who lives far away." I think about that sentence often. Maybe I can be a little more like Karim.`,
  },
  {
    id: 'oran-train', title: 'A train to Oran', category: 'Travel', level: 'A2', minutes: 2,
    blurb: 'A first trip, a shared meal, and a window full of fields.',
    text: `Last spring, I took the train from Algiers to Oran for the first time. I arrived at the station early because I was nervous. The ticket was cheap, and the seats were comfortable. A family sat across from me. The father offered me some dates and a piece of bread. We talked about football, of course. The trip took about four hours. Outside the window, I saw green fields, small villages, and the sea far away. When we arrived, the city was warm and bright. My cousin was waiting for me at the exit. She said, "You look tired, but happy." She was right. Sometimes the journey is as beautiful as the place you are going to.`,
  },
  {
    id: 'interview', title: 'Your first job interview', category: 'Work', level: 'B1', minutes: 3,
    blurb: 'Nadia did not get the job. Then she did something smart.',
    text: `Nadia was twenty-three when she got her first real job interview. She had prepared for days, but on the morning of the interview, her hands were shaking. The manager asked her a simple question: "Why should we choose you?" Nadia took a deep breath. She explained that she was reliable. When she promised to do something, she always did it, even when it was difficult. She told a short story about organizing a charity event at university with almost no budget. The manager listened carefully and took notes. Two weeks later, Nadia received an email. She had not got the job. She was disappointed, but she asked the manager for feedback. He replied kindly and said her answers were strong, but her English needed more practice. Nadia did not give up. She practiced every day for six months. Eventually, the same company offered her a new opportunity, and this time, she said yes.`,
  },
  {
    id: 'ramadan', title: 'Ramadan nights', category: 'Culture', level: 'B1', minutes: 3,
    blurb: 'How a city changes its rhythm after sunset.',
    text: `In many Algerian cities, Ramadan changes the rhythm of daily life. During the day, the streets are calm, and people save their energy. But just before sunset, everything becomes busy. Families hurry home, and the smell of chorba and fresh bread fills the air. When the call to prayer announces sunset, people break their fast, often with dates and milk. After the meal, the night comes alive. Some people go to the mosque for tarawih prayers. Others visit relatives or meet friends in cafés. Shops stay open late, and children play outside long after their usual bedtime. For many people, the most memorable part of Ramadan is not the food. It is the feeling of sharing: sharing meals, time, and patience with others. Visitors are often surprised by how generous people are. A stranger may be invited to break the fast with a family he met only an hour before.`,
  },
  {
    id: 'forgetting', title: 'Why we forget new words', category: 'Science', level: 'B2', minutes: 3,
    blurb: 'The forgetting curve, and how to bend it.',
    text: `Have you ever learned a new word, felt confident about it, and then completely forgotten it a few days later? You are not alone. In the nineteenth century, the German psychologist Hermann Ebbinghaus studied his own memory and described what is now called the forgetting curve. His experiments suggested that we lose a large part of new information within days unless we review it. The good news is that each review makes the memory stronger and slows down the forgetting. This is the idea behind spaced repetition. Instead of studying a word ten times in one evening, you review it just before you are likely to forget it: after one day, then three days, then a week, and so on. Another important factor is context. A word you meet in a real conversation or story, connected to emotions and images, is far easier to remember than a word on a list. In other words, the brain keeps what it believes is useful. If you want to remember a word, use it, connect it to your life, and let it come back to you at the right time.`,
  },
  {
    id: 'lighthouse', title: "The lighthouse keeper's last letter", category: 'Stories', level: 'B2', minutes: 3,
    blurb: 'Thirty-one years of light, and one letter to a stranger.',
    text: `For thirty-one years, Elias kept the lighthouse on a small rocky island. Every evening, he climbed one hundred and twelve steps to light the lamp, and every morning he wrote a short entry in his logbook: the weather, the ships he had seen, and sometimes a thought he wanted to keep. When the government announced that the lighthouse would become automatic, Elias was given three months to leave. He did not complain. Instead, he spent his last weeks writing a letter to whoever would read the logbook after him. "The light does not need me anymore," he wrote, "but I needed the light. It taught me to be patient, to pay attention to small changes in the sky, and to accept that most of my work would never be seen. Ships passed safely, and nobody knew my name. That is not sad. That is what it means to be useful." On his final morning, Elias closed the book, left it on the table, and took the boat to the mainland without looking back.`,
  },
];

export const LEVELS = [
  { id: 'A1', name: 'Starter', can: 'I know some words and very simple phrases.' },
  { id: 'A2', name: 'Elementary', can: 'I understand simple everyday sentences.' },
  { id: 'B1', name: 'Intermediate', can: 'I follow clear speech about familiar topics.' },
  { id: 'B2', name: 'Upper intermediate', can: 'I understand most podcasts if they speak clearly.' },
  { id: 'C1', name: 'Advanced', can: 'I understand fast, natural speech, but miss some words.' },
];

const ORDER = ['A1', 'A2', 'B1', 'B2', 'C1'];
export const levelDistance = (a, b) => Math.abs(ORDER.indexOf(a) - ORDER.indexOf(b));
