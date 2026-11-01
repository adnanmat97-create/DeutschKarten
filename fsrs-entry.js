import { createEmptyCard, fsrs, Rating, State } from 'ts-fsrs';

const scheduler = fsrs({
  request_retention: 0.9,
  maximum_interval: 36500,
  enable_fuzz: false,
  enable_short_term: true,
  learning_steps: ['1m', '10m'],
  relearning_steps: ['10m']
});

function hydrate(saved, now) {
  if (!saved) return createEmptyCard(new Date(now));
  return {
    due: new Date(saved.due || now),
    stability: Number(saved.stability || 0),
    difficulty: Number(saved.difficulty || 0),
    elapsed_days: Number(saved.elapsed_days || 0),
    scheduled_days: Number(saved.scheduled_days || 0),
    reps: Number(saved.reps || 0),
    lapses: Number(saved.lapses || 0),
    learning_steps: Number(saved.learning_steps || 0),
    state: Number.isInteger(saved.state) ? saved.state : State.New,
    last_review: saved.last_review ? new Date(saved.last_review) : undefined
  };
}
function serialize(card) {
  return {
    due: card.due.getTime(),
    stability: card.stability,
    difficulty: card.difficulty,
    elapsed_days: card.elapsed_days,
    scheduled_days: card.scheduled_days,
    reps: card.reps,
    lapses: card.lapses,
    learning_steps: card.learning_steps,
    state: card.state,
    last_review: card.last_review ? card.last_review.getTime() : null
  };
}
window.DKFSRS = {
  preview(saved, now) {
    const result = scheduler.repeat(hydrate(saved, now), new Date(now));
    return {
      1: Math.max(60000, result[Rating.Again].card.due.getTime() - now),
      2: Math.max(60000, result[Rating.Hard].card.due.getTime() - now),
      3: Math.max(60000, result[Rating.Good].card.due.getTime() - now),
      4: Math.max(60000, result[Rating.Easy].card.due.getTime() - now)
    };
  },
  review(saved, now, rating) {
    const result = scheduler.next(hydrate(saved, now), new Date(now), rating);
    return serialize(result.card);
  }
};
