import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { createContext, runInContext } from 'node:vm';

const shim = await readFile(new URL('./compat.js', import.meta.url), 'utf8');

function shimmedPromise() {
  const context = createContext();
  runInContext('Promise.allSettled = undefined;', context);
  runInContext(shim, context);
  return runInContext('Promise', context);
}

test('keeps native allSettled identity and descriptor', () => {
  const context = createContext();
  const PromiseConstructor = runInContext('Promise', context);
  const before = Object.getOwnPropertyDescriptor(PromiseConstructor, 'allSettled');
  runInContext(shim, context);
  assert.deepEqual(Object.getOwnPropertyDescriptor(PromiseConstructor, 'allSettled'), before);
});

test('fulfills in input order with values, thenables, resolution and rejection', async () => {
  const PromiseConstructor = shimmedPromise();
  let completeFirst;
  const first = new PromiseConstructor(resolve => { completeFirst = resolve; });
  const reason = new Error('expected rejection');
  const result = PromiseConstructor.allSettled(new Set([
    first,
    PromiseConstructor.reject(reason),
    42,
    { then(resolve) { resolve('thenable'); } },
  ]));
  completeFirst('first');
  const entries = await result;
  assert.deepEqual(Array.from(entries, entry => ({ ...entry })), [
    { status: 'fulfilled', value: 'first' },
    { status: 'rejected', reason },
    { status: 'fulfilled', value: 42 },
    { status: 'fulfilled', value: 'thenable' },
  ]);
  assert.equal(Object.getOwnPropertyDescriptor(PromiseConstructor, 'allSettled').enumerable, false);
});

test('empty iterable resolves immediately without an extra settling pass', async () => {
  const PromiseConstructor = shimmedPromise();
  const empty = PromiseConstructor.allSettled([]);
  assert.equal((await empty).length, 0);
  assert.equal(await PromiseConstructor.race([
    PromiseConstructor.allSettled([]).then(() => 'empty'),
    PromiseConstructor.resolve().then(() => 'other'),
  ]), 'empty');
});

test('bad iterables and throwing iterators reject instead of returning empty results', async () => {
  const PromiseConstructor = shimmedPromise();
  for (const value of [null, undefined, {}, 42, { [Symbol.iterator]: 1 }]) {
    await assert.rejects(PromiseConstructor.allSettled(value), error => error.name === 'TypeError');
  }
  const failure = new Error('iterator failed');
  await assert.rejects(PromiseConstructor.allSettled({
    *[Symbol.iterator]() { yield 1; throw failure; },
  }), error => error === failure);
});

test('thenables cannot settle twice and rejected then getters are reported', async () => {
  const PromiseConstructor = shimmedPromise();
  const failure = new Error('then getter failed');
  const entries = await PromiseConstructor.allSettled([
    { then(resolve, reject) { resolve('once'); reject('twice'); resolve('third'); } },
    { get then() { throw failure; } },
  ]);
  assert.deepEqual(Array.from(entries, entry => ({ ...entry })), [
    { status: 'fulfilled', value: 'once' },
    { status: 'rejected', reason: failure },
  ]);
});
