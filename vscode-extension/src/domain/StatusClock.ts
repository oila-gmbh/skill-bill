export interface StatusClock {
  now(): Date;
}

export const StatusClock = {
  system(): StatusClock {
    return { now: () => new Date() };
  },

  fixed(instant: Date): StatusClock {
    return { now: () => instant };
  },
};
