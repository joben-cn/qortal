package org.qortal.test;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.Block;
import org.qortal.block.GenesisBlock;
import org.qortal.data.at.ATStateData;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transform.TransformationException;
import org.qortal.transform.block.BlockTransformer;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.Triple;

import static org.junit.Assert.*;

public class BlockTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testGenesisBlockTransactions() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			GenesisBlock block = GenesisBlock.getInstance(repository);

			assertNotNull(block);
			assertTrue(block.isSignatureValid());
			// only true if blockchain is empty
			// assertTrue(block.isValid());

			List<Transaction> transactions = block.getTransactions();
			assertNotNull(transactions);

			byte[] lastGenesisSignature = null;
			for (Transaction transaction : transactions) {
				assertNotNull(transaction);

				TransactionData transactionData = transaction.getTransactionData();

				if (transactionData.getType() != Transaction.TransactionType.GENESIS)
					continue;

				assertEquals(0L, (long) transactionData.getFee());
				assertTrue(transaction.isSignatureValid());
				assertEquals(Transaction.ValidationResult.OK, transaction.isValid());

				lastGenesisSignature = transactionData.getSignature();
			}

			// Attempt to load last GENESIS transaction directly from database
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(lastGenesisSignature);
			assertNotNull(transactionData);

			assertEquals(Transaction.TransactionType.GENESIS, transactionData.getType());
			assertEquals(0L, (long) transactionData.getFee());
			// assertNull(transactionData.getReference());

			Transaction transaction = Transaction.fromData(repository, transactionData);
			assertNotNull(transaction);

			assertTrue(transaction.isSignatureValid());
			assertEquals(Transaction.ValidationResult.OK, transaction.isValid());
		}
	}

	@Test
	public void testBlockSerialization() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount signingAccount = Common.getTestAccount(repository, "alice");

			// TODO: Fill block with random, valid transactions of every type (except GENESIS, ACCOUNT_FLAGS or AT)
			// This isn't as trivial as it seems as some transactions rely on others.
			// e.g. CANCEL_ASSET_ORDER needs a prior CREATE_ASSET_ORDER
			for (Transaction.TransactionType txType : Transaction.TransactionType.values()) {
				if (txType == TransactionType.GENESIS || txType == TransactionType.ACCOUNT_FLAGS || txType == TransactionType.AT)
					continue;

				TransactionData transactionData = TransactionUtils.randomTransaction(repository, signingAccount, txType, true);
				Transaction transaction = Transaction.fromData(repository, transactionData);
				transaction.sign(signingAccount);

				Transaction.ValidationResult validationResult = transaction.importAsUnconfirmed();
				if (validationResult != Transaction.ValidationResult.OK)
					fail(String.format("Invalid (%s) test transaction, type %s", validationResult.name(), txType.name()));
			}

			// We might need to wait until transactions' timestamps are valid for the block we're about to generate
			try {
				Thread.sleep(1L);
			} catch (InterruptedException e) {
			}

			BlockUtils.mintBlock(repository);

			BlockData blockData = repository.getBlockRepository().getLastBlock();
			Block block = new Block(repository, blockData);
			assertTrue(block.isSignatureValid());

			byte[] bytes = BlockTransformer.toBytes(block);

			assertEquals(BlockTransformer.getDataLength(block), bytes.length);

			Triple<BlockData, List<TransactionData>, List<ATStateData>> blockInfo = BlockTransformer.fromBytes(bytes);

			// Compare transactions
			List<TransactionData> deserializedTransactions = blockInfo.getB();
			assertEquals("Transaction count differs", blockData.getTransactionCount(), deserializedTransactions.size());

			for (int i = 0; i < blockData.getTransactionCount(); ++i) {
				TransactionData deserializedTransactionData = deserializedTransactions.get(i);
				Transaction originalTransaction = block.getTransactions().get(i);
				TransactionData originalTransactionData = originalTransaction.getTransactionData();

				assertEquals("Transaction signature differs", Base58.encode(originalTransactionData.getSignature()), Base58.encode(deserializedTransactionData.getSignature()));
				assertEquals("Transaction declared length differs", TransactionTransformer.getDataLength(originalTransactionData), TransactionTransformer.getDataLength(deserializedTransactionData));
				assertEquals("Transaction serialized length differs", TransactionTransformer.toBytes(originalTransactionData).length, TransactionTransformer.toBytes(deserializedTransactionData).length);
			}
		}
	}

	@Test
	public void testCommonBlockSearch() {
		// Given a list of block summaries, trim all trailing summaries after common block

		// We'll represent known block summaries as a list of booleans,
		// where the boolean value indicates whether peer's block is also in our repository.

		// Trivial case, single element array
		assertCommonBlock(0, new boolean[] { true });

		// Test odd and even array lengths
		for (int arrayLength = 5; arrayLength <= 6; ++arrayLength) {
			boolean[] testBlocks = new boolean[arrayLength];

			// Test increasing amount of common blocks
			for (int c = 1; c <= testBlocks.length; ++c) {
				testBlocks[c - 1] = true;

				assertCommonBlock(c - 1, testBlocks);
			}
		}
	}

	private void assertCommonBlock(int expectedIndex, boolean[] testBlocks) {
		int commonBlockIndex = findCommonBlockIndex(testBlocks);
		assertEquals(expectedIndex, commonBlockIndex);
	}

	private int findCommonBlockIndex(boolean[] testBlocks) {
		int low = 1;
		int high = testBlocks.length - 1;

		while (low <= high) {
			int mid = (low + high) >>> 1;

			if (testBlocks[mid])
				low = mid + 1;
			else
				high = mid - 1;
		}

		return low - 1;
	}

}
