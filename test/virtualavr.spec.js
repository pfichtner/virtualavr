const { runCode } = require('../virtualavr');

describe('My Test Suite', () => {
	it('My Test Case', () => {
		const mockFunction = jest.fn()
		runCode('./code.ino', mockFunction);
		expect(mockFunction).toHaveBeenCalledWith(13, '1');
		expect(mockFunction).toHaveBeenCalledWith(13, '0');
	});
});

