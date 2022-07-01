const { runCode } = require('../virtualavr');
const waitForExpect = require("wait-for-expect");

describe('My Test Suite', () => {
	it('My Test Case', async () => {
		const mockFunction = jest.fn()
		runCode('./code.ino', mockFunction);
		await waitForExpect(() => {
			expect(mockFunction).toHaveBeenCalledWith(13, '1');
			expect(mockFunction).toHaveBeenCalledWith(13, '0');
		});
	});	
});

