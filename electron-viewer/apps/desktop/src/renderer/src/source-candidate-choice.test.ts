import { describe, expect, it } from 'vitest';
import { sourceCandidateChoice } from './source-candidate-choice';

describe('sourceCandidateChoice', () => {
  it('opens one cited candidate directly and presents multiple candidates for selection', () => {
    expect(sourceCandidateChoice([])).toEqual({ kind: 'none' });
    expect(sourceCandidateChoice(['source-a'])).toEqual({ kind: 'open', candidateId: 'source-a' });
    expect(sourceCandidateChoice(['source-a', 'source-b', 'source-a'])).toEqual({
      kind: 'choose',
      candidateIds: ['source-a', 'source-b'],
    });
  });
});
