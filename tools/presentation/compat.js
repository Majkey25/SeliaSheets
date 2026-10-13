// Chrome 74 lacks Promise.allSettled. Keep native implementations unchanged.
if (typeof Promise.allSettled !== 'function') {
  Object.defineProperty(Promise, 'allSettled', {
    configurable: true,
    writable: true,
    value: function allSettled(iterable) {
      const Constructor = this;
      return new Constructor((resolve) => {
        const results = [];
        const resolveValue = Constructor.resolve;
        let remaining = 1;
        let index = 0;
        for (const value of iterable) {
          const position = index++;
          remaining++;
          let settled = false;
          const finish = result => {
            if (settled) return;
            settled = true;
            results[position] = result;
            if (--remaining === 0) resolve(results);
          };
          resolveValue.call(Constructor, value).then(
            value => finish({ status: 'fulfilled', value }),
            reason => finish({ status: 'rejected', reason }),
          );
        }
        if (--remaining === 0) resolve(results);
      });
    },
  });
}
