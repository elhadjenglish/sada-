-- Sada: Supabase schema for account sync. NOT CONNECTED YET: the app stores data on the device only.
-- See docs/DATABASE_PLAN.md for the integration and migration plan.

create table if not exists profiles (
  id uuid primary key references auth.users on delete cascade,
  name text,
  level text check (level in ('A1','A2','B1','B2','C1')) default 'B1',
  explain_lang text check (explain_lang in ('ar','en','both')) default 'both',
  wake_phrase text default 'Hey Sada',
  created_at timestamptz default now()
);

create table if not exists words (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users on delete cascade,
  -- id the word had on the device; makes the first upload idempotent (re-running it can't duplicate words)
  local_id text,
  word text not null,
  lemma text,
  pos text,
  ipa text,
  meaning_en text,
  meaning_ar text,
  example text,
  asks int not null default 1,
  -- spaced repetition state
  ease real not null default 2.5,
  interval_days int not null default 0,
  reps int not null default 0,
  lapses int not null default 0,
  due_at timestamptz not null default now(),
  -- Stage 3: the learner chooses exactly which words are visible on their profile (no read policy yet; see plan)
  is_public boolean not null default false,
  created_at timestamptz default now(),
  last_asked_at timestamptz default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (user_id, local_id)
);
create index if not exists words_due on words (user_id, due_at);

create table if not exists word_contexts (
  id bigserial primary key,
  word_id uuid not null references words on delete cascade,
  user_id uuid not null references auth.users on delete cascade,
  local_key text, -- hash of sentence + episode, so re-uploads don't duplicate contexts
  sentence text not null,
  source_type text default 'episode',
  source_title text,
  episode_id text,
  heard_at timestamptz default now(),
  unique (word_id, local_key)
);

create table if not exists reviews (
  id bigserial primary key,
  word_id uuid not null references words on delete cascade,
  user_id uuid not null references auth.users on delete cascade,
  quality smallint not null,
  via text,
  reviewed_at timestamptz default now()
);

create table if not exists episode_progress (
  user_id uuid references auth.users on delete cascade,
  episode_id text,
  position int default 0,
  total int,
  done boolean default false,
  updated_at timestamptz default now(),
  primary key (user_id, episode_id)
);

alter table profiles enable row level security;
alter table words enable row level security;
alter table word_contexts enable row level security;
alter table reviews enable row level security;
alter table episode_progress enable row level security;

create policy "own profile" on profiles for all using (auth.uid() = id) with check (auth.uid() = id);
create policy "own words" on words for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
-- No public read policy on purpose: a policy on the words table would expose user_id and every column.
-- Stage 3 will expose public words through a view that returns only word + display name.
create policy "own contexts" on word_contexts for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own reviews" on reviews for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own progress" on episode_progress for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
